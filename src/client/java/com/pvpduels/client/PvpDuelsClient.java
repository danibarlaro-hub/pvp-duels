package com.pvpduels.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.net.CycleLayoutPayload;
import com.pvpduels.net.SortRequestPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class PvpDuelsClient implements ClientModInitializer {
	private static KeyMapping sortKey;
	private static KeyMapping prevLayoutKey;
	private static KeyMapping nextLayoutKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(PvpDuelsMod.MOD_ID, "main"));

		sortKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.pvpduels.sort",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			category));

		// Unbound by default so they never clash with your other keys; set them in Controls.
		prevLayoutKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.pvpduels.layout_prev",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_UNKNOWN,
			category));

		nextLayoutKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.pvpduels.layout_next",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_UNKNOWN,
			category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (sortKey.consumeClick()) {
				if (client.player != null) {
					ClientPlayNetworking.send(new SortRequestPayload());
				}
			}
			while (prevLayoutKey.consumeClick()) {
				if (client.player != null) {
					ClientPlayNetworking.send(new CycleLayoutPayload(false));
				}
			}
			while (nextLayoutKey.consumeClick()) {
				if (client.player != null) {
					ClientPlayNetworking.send(new CycleLayoutPayload(true));
				}
			}
		});
	}
}
