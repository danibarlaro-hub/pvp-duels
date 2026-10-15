package com.pvpduels;

import com.pvpduels.duel.DuelCommands;
import com.pvpduels.duel.DuelManager;
import com.pvpduels.achievements.AchievementCommands;
import com.pvpduels.achievements.Achievements;
import com.pvpduels.arena.ArenaCommands;
import com.pvpduels.arena.ArenaStudio;
import com.pvpduels.arena.BuilderRank;
import com.pvpduels.arena.Contest;
import com.pvpduels.duel.BotDuel;
import com.pvpduels.duel.Lobby;
import com.pvpduels.duel.LobbyKit;
import com.pvpduels.duel.Npcs;
import com.pvpduels.duel.Party;
import com.pvpduels.effects.EffectCommands;
import com.pvpduels.effects.Missions;
import com.pvpduels.effects.Victory;
import com.pvpduels.duel.Queue;
import com.pvpduels.duel.ReplayCommands;
import com.pvpduels.duel.Spectators;
import com.pvpduels.duel.Terrain;
import com.pvpduels.duel.SelfTest;
import com.pvpduels.duel.TestDummy;
import com.pvpduels.kit.KitCommands;
import com.pvpduels.kit.ShareCommands;
import com.pvpduels.layout.InventorySorter;
import com.pvpduels.worldgen.IslandBuilder;
import com.pvpduels.layout.LayoutCommands;
import com.pvpduels.stats.TopCommand;
import com.pvpduels.net.CycleLayoutPayload;
import com.pvpduels.net.FilmKeyPayload;
import com.pvpduels.net.QuickKitPayload;
import com.pvpduels.net.QuickLayoutPayload;
import com.pvpduels.net.SortRequestPayload;
import com.pvpduels.ui.Sidebar;
import com.pvpduels.util.Msg;
import com.pvpduels.workshop.ItemCommands;
import com.pvpduels.workshop.Workshop;
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
		PayloadTypeRegistry.serverboundPlay().register(FilmKeyPayload.TYPE, FilmKeyPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SortRequestPayload.TYPE, (payload, context) ->
			context.server().execute(() -> InventorySorter.sortAndReport(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(CycleLayoutPayload.TYPE, (payload, context) ->
			context.server().execute(() -> LayoutCommands.cycle(context.player(), payload.forward())));

		ServerPlayNetworking.registerGlobalReceiver(QuickLayoutPayload.TYPE, (payload, context) ->
			context.server().execute(() -> LayoutCommands.quick(context.player(), payload.index())));

		ServerPlayNetworking.registerGlobalReceiver(FilmKeyPayload.TYPE, (payload, context) ->
			context.server().execute(() -> com.pvpduels.duel.Cinema.filmKey(context.player(), payload.action())));

		ServerPlayNetworking.registerGlobalReceiver(QuickKitPayload.TYPE, (payload, context) ->
			context.server().execute(() -> KitCommands.quick(context.server(), context.player(), payload.index())));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			KitCommands.register(dispatcher);
			ShareCommands.register(dispatcher);
			LayoutCommands.register(dispatcher);
			GuideCommand.register(dispatcher);
			DuelCommands.register(dispatcher);
			TestDummy.register(dispatcher);
			Workshop.registerCommands(dispatcher);
			Sidebar.registerCommands(dispatcher);
			Lobby.registerCommands(dispatcher);
			Spectators.registerCommands(dispatcher);
			Npcs.registerCommands(dispatcher);
			Queue.registerCommands(dispatcher);
			ArenaStudio.registerCommands(dispatcher);
			ArenaCommands.register(dispatcher);
			BuilderRank.registerCommands(dispatcher);
			Contest.registerCommands(dispatcher);
			AchievementCommands.register(dispatcher);
			Party.registerCommands(dispatcher);
			EffectCommands.register(dispatcher);
			BotDuel.registerCommands(dispatcher);
			ReplayCommands.register(dispatcher);
			TopCommand.register(dispatcher);
			com.pvpduels.stats.HeadToHead.register(dispatcher);
			com.pvpduels.stats.Seasons.register(dispatcher);
			com.pvpduels.util.BugReports.register(dispatcher);
			com.pvpduels.util.BugBoard.register(dispatcher);
			com.pvpduels.util.Homes.register(dispatcher);
			com.pvpduels.effects.Pranks.register(dispatcher);
			ItemCommands.register(dispatcher, registryAccess);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			handler.getPlayer().sendSystemMessage(Msg.info("PvP Duels is active. Type /guide to see the commands.")));

		// The lobby items and the NPCs go first: their clicks must be handled before the lobby protection says no.
		LobbyKit.register();
		Npcs.register();
		Queue.register();
		ArenaStudio.register();
		Contest.register();
		Achievements.register();
		Party.register();
		Missions.register();
		com.pvpduels.stats.Seasons.register();
		ReplayCommands.register();
		Victory.register();
		com.pvpduels.duel.Cinema.register();
		DuelManager.register();
		com.pvpduels.util.Homes.init();
		com.pvpduels.util.BugBoard.register();
		com.pvpduels.effects.Pranks.init();
		Terrain.register();
		Spectators.register();
		BotDuel.register();
		TestDummy.registerEvents();
		Workshop.register();
		GuideBook.register();
		Sidebar.register();
		SelfTest.register();
		IslandBuilder.register();

		LOGGER.info("PvP Duels loaded.");
	}
}
