package com.pvpduels;

import com.pvpduels.kit.KitCommands;
import com.pvpduels.layout.InventorySorter;
import com.pvpduels.layout.LayoutCommands;
import com.pvpduels.net.CycleLayoutPayload;
import com.pvpduels.net.QuickKitPayload;
import com.pvpduels.net.QuickLayoutPayload;
import com.pvpduels.net.SortRequestPayload;
import com.pvpduels.util.Msg;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PvpDuelsMod implements ModInitializer {
	public static final String MOD_ID = "pvpduels";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Client -> server packets used by the keys.
		PayloadTypeRegistry.serverboundPlay().register(SortRequestPayload.TYPE, SortRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CycleLayoutPayload.TYPE, CycleLayoutPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(QuickLayoutPayload.TYPE, QuickLayoutPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(QuickKitPayload.TYPE, QuickKitPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SortRequestPayload.TYPE, (payload, context) ->
			context.server().execute(() -> InventorySorter.sortAndReport(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(CycleLayoutPayload.TYPE, (payload, context) ->
			context.server().execute(() -> LayoutCommands.cycle(context.player(), payload.forward())));

		ServerPlayNetworking.registerGlobalReceiver(QuickLayoutPayload.TYPE, (payload, context) ->
			context.server().execute(() -> LayoutCommands.quick(context.player(), payload.index())));

		ServerPlayNetworking.registerGlobalReceiver(QuickKitPayload.TYPE, (payload, context) ->
			context.server().execute(() -> KitCommands.quick(context.server(), context.player(), payload.index())));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			KitCommands.register(dispatcher);
			LayoutCommands.register(dispatcher);
			GuideCommand.register(dispatcher);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			handler.getPlayer().sendSystemMessage(Msg.info("PvP Duels is active. Type /guide to see the commands.")));

		LOGGER.info("PvP Duels loaded.");
	}
}
