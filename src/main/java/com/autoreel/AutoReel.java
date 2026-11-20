package com.autoreel;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Random;

/**
 * Reel-only auto fishing. When a fish bites your own bobber, it uses the rod once to reel in.
 * It never casts, so you decide when to fish.
 */
public final class AutoReel implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("autoreel");
	private static final Random RNG = new Random();
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("autoreel.properties");

	private static KeyMapping toggleKey;

	// Settings (saved to config/autoreel.properties)
	private static boolean enabled = true;
	private static int minDelayTicks = 2;
	private static int maxDelayTicks = 5;

	private static boolean accessorLookedUp = false;
	private static EntityDataAccessor<?> bitingAccessor = null;

	/** -1 = nothing pending; otherwise ticks left until we reel. */
	private static int pendingReelTicks = -1;
	/** Entity id of the hook we've already reeled for, so one bite = one reel. */
	private static int handledHookId = Integer.MIN_VALUE;

	@Override
	public void onInitializeClient() {
		loadConfig();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("autoreel", "main"));
		// Unbound by default; set it in Options > Controls > Key Binds.
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autoreel.toggle",
				InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));

		ClientTickEvents.END_CLIENT_TICK.register(AutoReel::onTick);
	}

	private static void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;

		while (toggleKey.consumeClick()) {
			enabled = !enabled;
			saveConfig();
			if (player != null) {
				player.sendOverlayMessage(Component.literal("Auto Reel: " + (enabled ? "ON" : "OFF"))
						.withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
			}
		}

		if (!enabled || player == null || mc.level == null || mc.gameMode == null) {
			pendingReelTicks = -1;
			return;
		}

		FishingHook hook = player.fishing;
		if (hook == null) {
			// Reeled manually, switched items, or the hook despawned.
			pendingReelTicks = -1;
			return;
		}

		if (pendingReelTicks > 0) {
			pendingReelTicks--;
			return;
		}
		if (pendingReelTicks == 0) {
			pendingReelTicks = -1;
			reel(mc, player);
			return;
		}

		if (hook.getId() != handledHookId && isBiting(hook)) {
			handledHookId = hook.getId();
			int max = Math.max(minDelayTicks, maxDelayTicks);
			pendingReelTicks = minDelayTicks + RNG.nextInt(max - minDelayTicks + 1);
		}
	}

	private static void reel(Minecraft mc, LocalPlayer player) {
		if (player.fishing == null) return;

		InteractionHand hand;
		if (player.getMainHandItem().getItem() instanceof FishingRodItem) {
			hand = InteractionHand.MAIN_HAND;
		} else if (player.getOffhandItem().getItem() instanceof FishingRodItem) {
			hand = InteractionHand.OFF_HAND;
		} else {
			hand = InteractionHand.MAIN_HAND;
		}

		InteractionResult result = mc.gameMode.useItem(player, hand);
		if (result.consumesAction()) {
		}
	}

	/** Reads FishingHook's synced "biting" flag, which the server sets when a fish is on the line. */
	private static boolean isBiting(FishingHook hook) {
		if (!accessorLookedUp) {
			accessorLookedUp = true;
			bitingAccessor = findBitingAccessor(hook);
			if (bitingAccessor == null) LOGGER.warn("Auto Reel: couldn't find the bobber's bite flag; auto reel won't work.");
		}
		if (bitingAccessor == null) return false;
		try {
			return Boolean.TRUE.equals(hook.getEntityData().get(bitingAccessor));
		} catch (Exception e) {
			return false;
		}
	}

	private static EntityDataAccessor<?> findBitingAccessor(FishingHook hook) {
		// 26.x is unobfuscated, so the field should be called DATA_BITING.
		try {
			Field f = FishingHook.class.getDeclaredField("DATA_BITING");
			f.setAccessible(true);
			return (EntityDataAccessor<?>) f.get(null);
		} catch (Exception ignored) {
		}
		// Fallback: the only boolean synced field FishingHook declares is the bite flag.
		for (Field f : FishingHook.class.getDeclaredFields()) {
			if (!Modifier.isStatic(f.getModifiers()) || !EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
			try {
				f.setAccessible(true);
				EntityDataAccessor<?> acc = (EntityDataAccessor<?>) f.get(null);
				if (hook.getEntityData().get(acc) instanceof Boolean) return acc;
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	private static void loadConfig() {
		Properties p = new Properties();
		if (Files.exists(CONFIG)) {
			try (Reader r = Files.newBufferedReader(CONFIG)) {
				p.load(r);
				enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
				minDelayTicks = clamp(Integer.parseInt(p.getProperty("minReelDelayTicks", "2").trim()));
				maxDelayTicks = clamp(Integer.parseInt(p.getProperty("maxReelDelayTicks", "5").trim()));
			} catch (Exception e) {
				LOGGER.warn("Auto Reel: couldn't read config, using defaults", e);
			}
		}
		saveConfig();
	}

	private static void saveConfig() {
		Properties p = new Properties();
		p.setProperty("enabled", Boolean.toString(enabled));
		p.setProperty("minReelDelayTicks", Integer.toString(minDelayTicks));
		p.setProperty("maxReelDelayTicks", Integer.toString(maxDelayTicks));
		try (Writer w = Files.newBufferedWriter(CONFIG)) {
			p.store(w, "Auto Reel settings. Delays are in ticks (20 = 1 second); keep them at 15 or less.");
		} catch (Exception e) {
			LOGGER.warn("Auto Reel: couldn't save config", e);
		}
	}

	private static int clamp(int ticks) {
		return Math.max(0, Math.min(15, ticks));
	}
}
