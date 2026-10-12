package com.pvpduels.duel;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A fake player for testing duels alone. Admin-only command: /test_dummy &lt;name&gt; (and /test_dummy remove [name]).
 * The dummy stands still, never fights back, and accepts every duel request or rematch automatically.
 * The same fake player class is used by the automatic self-test.
 */
public final class TestDummy {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final int MAX_DUMMIES = 4;
	/** Dummies spawned by the command (they auto-accept duels). Name to player id. */
	private static final Map<String, UUID> AUTO = new HashMap<>();

	private TestDummy() {}

	// ------------------------------------------------------------------ the fake player

	/** A network connection that goes nowhere. */
	static final class FakeConnection extends Connection {
		FakeConnection() {
			super(PacketFlow.SERVERBOUND);
		}

		@Override public void send(Packet<?> packet) {}
		@Override public void send(Packet<?> packet, ChannelFutureListener listener) {}
		@Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}
		@Override public void setReadOnly() {}
		@Override public void handleDisconnection() {}
		@Override public void setListenerForServerboundHandshake(PacketListener listener) {}
		@Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {}
	}

	static final class Dummy extends ServerPlayer {
		/** The last messages this player received (the self-test reads them). */
		final List<String> inbox = new ArrayList<>();

		Dummy(MinecraftServer server, ServerLevel level, GameProfile profile) {
			super(server, level, profile, ClientInformation.createDefault());
		}

		/** A real client tells the server when it has loaded the world; until then players can't be hurt. */
		void markLoaded() {
			if (isChangingDimension()) hasChangedDimension(); // a real client does this with its first movement packet
			if (connection != null && !connection.hasClientLoaded()) {
				connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
			}
		}

		@Override
		public void tick() {
			markLoaded();
			super.tick();
			doTick();
		}

		@Override
		public void sendSystemMessage(Component message, boolean overlay) {
			inbox.add(message.getString());
			if (inbox.size() > 300) inbox.remove(0);
			super.sendSystemMessage(message, overlay);
		}

		boolean heard(String text) {
			for (String line : inbox) if (line.contains(text)) return true;
			return false;
		}
	}

	/** Joins a fake player with this name (it appears in the player list like a real one). */
	static Dummy create(MinecraftServer server, String name) {
		GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
		Dummy dummy = new Dummy(server, server.overworld(), profile);
		server.getPlayerList().placeNewPlayer(new FakeConnection(), dummy, CommonListenerCookie.createInitial(profile, false));
		return dummy;
	}

	// ------------------------------------------------------------------ used by the duel code

	/** True for dummies made with the command: they accept every challenge. */
	public static boolean accepts(UUID id) {
		return AUTO.containsValue(id);
	}

	// ------------------------------------------------------------------ spawning / removing

	/** Returns an error text, or null when the dummy was created. */
	static String spawn(ServerPlayer owner, String name) {
		MinecraftServer server = owner.level().getServer();
		if (!NAME.matcher(name).matches()) return "A name can only have letters, numbers and _ (up to 16 characters).";
		if (DuelManager.isInDuel(owner.getUUID())) return "You can't do that during a duel.";
		if (server.getPlayerList().getPlayerByName(name) != null) return "A player named '" + name + "' is already online.";
		if (AUTO.size() >= MAX_DUMMIES) return "There are already " + MAX_DUMMIES + " test dummies. Remove one with /test_dummy remove <name>.";

		Dummy dummy = create(server, name);
		AUTO.put(name.toLowerCase(), dummy.getUUID());
		dummy.setGameMode(GameType.SURVIVAL);
		dummy.teleportTo((ServerLevel) owner.level(), owner.getX() + 2.5, owner.getY(), owner.getZ(),
			Set.of(), owner.getYRot() + 180f, 0f, true);
		PvpDuelsMod.LOGGER.info("Test dummy '{}' created by {}", name, owner.getName().getString());
		return null;
	}

	/** Removes one dummy (name given) or all of them. Returns how many were removed. */
	static int remove(MinecraftServer server, String name) {
		List<String> names = new ArrayList<>();
		if (name == null) names.addAll(AUTO.keySet());
		else if (AUTO.containsKey(name.toLowerCase())) names.add(name.toLowerCase());
		int removed = 0;
		for (String key : names) {
			UUID id = AUTO.remove(key);
			ServerPlayer p = id == null ? null : server.getPlayerList().getPlayer(id);
			if (p == null) continue;
			Duel duel = DuelManager.duelOf(id);
			if (duel != null) {
				// Leaves the duel first so the other player is not left waiting.
				DuelManager.leave(p);
			}
			if (p.connection != null) p.connection.onDisconnect(new DisconnectionDetails(Component.literal("Test dummy removed")));
			removed++;
		}
		return removed;
	}

	// ------------------------------------------------------------------ command

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		for (String root : new String[] {"test_dummy", "fakeplayer"}) {
			LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal(root)
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("remove")
					.executes(ctx -> {
						int n = remove(ctx.getSource().getServer(), null);
						ctx.getSource().sendSuccess(() -> Msg.ok("Removed " + n + " test dummies."), false);
						return n;
					})
					.then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
						String name = StringArgumentType.getString(ctx, "name");
						int n = remove(ctx.getSource().getServer(), name);
						if (n == 0) {
							ctx.getSource().sendFailure(Msg.err("There is no test dummy named '" + name + "'."));
							return 0;
						}
						ctx.getSource().sendSuccess(() -> Msg.ok("Removed the test dummy '" + name + "'."), false);
						return 1;
					})))
				.then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
					ServerPlayer owner = ctx.getSource().getPlayerOrException();
					String name = StringArgumentType.getString(ctx, "name");
					String problem = spawn(owner, name);
					if (problem != null) {
						ctx.getSource().sendFailure(Msg.err(problem));
						return 0;
					}
					ctx.getSource().sendSuccess(() -> Msg.ok("Test dummy '" + name + "' created next to you. Challenge it with /duel "
						+ name + ". It never fights back and accepts every duel. Remove it with /test_dummy remove " + name + "."), false);
					return 1;
				}));
			dispatcher.register(cmd);
		}
	}

	/** Takes the dummies out when the server stops (after the running duels were put back). */
	public static void registerEvents() {
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> remove(server, null));
	}
}
