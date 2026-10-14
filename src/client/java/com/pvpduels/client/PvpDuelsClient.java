package com.pvpduels.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.net.CycleLayoutPayload;
import com.pvpduels.net.FilmKeyPayload;
import com.pvpduels.net.QuickKitPayload;
import com.pvpduels.net.QuickLayoutPayload;
import com.pvpduels.net.SortRequestPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** All keys are normal Minecraft key binds: change them in Options > Controls > Key Binds > PvP Duels. */
public class PvpDuelsClient implements ClientModInitializer {
	private static KeyMapping sortKey;
	private static KeyMapping prevLayoutKey;
	private static KeyMapping nextLayoutKey;
	private static KeyMapping skipSceneKey;
	private static KeyMapping freeCamKey;
	private static final KeyMapping[] QUICK_KEYS = new KeyMapping[3];
	private static final KeyMapping[] KIT_KEYS = new KeyMapping[3];

	private static KeyMapping register(String name, int defaultKey, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(
			name, InputConstants.Type.KEYSYM, defaultKey, category));
	}

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(PvpDuelsMod.MOD_ID, "main"));

		sortKey = register("key.pvpduels.sort", GLFW.GLFW_KEY_R, category);

		// Unbound by default so they never clash with your other keys; set them in Controls.
		for (int i = 0; i < QUICK_KEYS.length; i++) {
			QUICK_KEYS[i] = register("key.pvpduels.quick_layout_" + (i + 1), GLFW.GLFW_KEY_UNKNOWN, category);
		}
		String[] letters = {"a", "b", "c"};
		for (int i = 0; i < KIT_KEYS.length; i++) {
			KIT_KEYS[i] = register("key.pvpduels.quick_kit_" + letters[i], GLFW.GLFW_KEY_UNKNOWN, category);
		}
		prevLayoutKey = register("key.pvpduels.layout_prev", GLFW.GLFW_KEY_UNKNOWN, category);
		nextLayoutKey = register("key.pvpduels.layout_next", GLFW.GLFW_KEY_UNKNOWN, category);

		// The keys of the films (the end of a duel with an explosion, the Explosion Dummy, /effects preview).
		skipSceneKey = register("key.pvpduels.skip_scene", GLFW.GLFW_KEY_RIGHT_SHIFT, category);
		freeCamKey = register("key.pvpduels.free_cam", GLFW.GLFW_KEY_LEFT_CONTROL, category);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean inGame = client.player != null;
			// a film puts you in spectator mode, so a press outside of one is not even sent
			boolean watching = inGame && client.player.isSpectator();
			while (skipSceneKey.consumeClick()) {
				if (watching) ClientPlayNetworking.send(new FilmKeyPayload(FilmKeyPayload.SKIP));
			}
			while (freeCamKey.consumeClick()) {
				if (watching) ClientPlayNetworking.send(new FilmKeyPayload(FilmKeyPayload.FREE_CAM));
			}
			while (sortKey.consumeClick()) {
				if (inGame) ClientPlayNetworking.send(new SortRequestPayload());
			}
			for (int i = 0; i < QUICK_KEYS.length; i++) {
				while (QUICK_KEYS[i].consumeClick()) {
					if (inGame) ClientPlayNetworking.send(new QuickLayoutPayload(i));
				}
			}
			for (int i = 0; i < KIT_KEYS.length; i++) {
				while (KIT_KEYS[i].consumeClick()) {
					if (inGame) ClientPlayNetworking.send(new QuickKitPayload(i));
				}
			}
			while (prevLayoutKey.consumeClick()) {
				if (inGame) ClientPlayNetworking.send(new CycleLayoutPayload(false));
			}
			while (nextLayoutKey.consumeClick()) {
				if (inGame) ClientPlayNetworking.send(new CycleLayoutPayload(true));
			}
		});
	}
}
