package com.pvpduels.duel;

import com.mojang.authlib.GameProfile;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.kit.KitCommands;
import com.pvpduels.kit.KitStore;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Automatic test that only runs when the environment variable PVPDUELS_SELFTEST is set (it is never set in the game).
 * GitHub's build starts a dedicated server with two fake players and plays complete duels, then writes
 * selftest-result.txt. PVPDUELS_SELFTEST=stop only boots the server and stops it again (to create a world).
 */
public final class SelfTest {
	private SelfTest() {}

	// ------------------------------------------------------------------ fake players

	private static final class FakeConnection extends Connection {
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

	private static final class Bot extends ServerPlayer {
		final List<String> inbox = new ArrayList<>();

		Bot(MinecraftServer server, ServerLevel level, GameProfile profile) {
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
			super.sendSystemMessage(message, overlay);
		}

		boolean heard(String text) {
			for (String line : inbox) if (line.contains(text)) return true;
			return false;
		}
	}

	private static Bot spawn(String name) {
		GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
		Bot bot = new Bot(server, server.overworld(), profile);
		server.getPlayerList().placeNewPlayer(new FakeConnection(), bot, CommonListenerCookie.createInitial(profile, false));
		return bot;
	}

	// ------------------------------------------------------------------ test runner

	private record Step(String name, Runnable action, BooleanSupplier until, int timeout) {}

	private static MinecraftServer server;
	private static final List<Step> STEPS = new ArrayList<>();
	private static final List<String> REPORT = new ArrayList<>();
	private static int index = -1;
	private static int waited;
	private static int ticks;
	private static int failures;
	private static boolean done;
	private static boolean running;

	public static void register() {
		String mode = System.getenv("PVPDUELS_SELFTEST");
		if (mode == null || mode.isEmpty()) return;
		PvpDuelsMod.LOGGER.warn("SELFTEST mode '{}' is on (this must never happen in the game).", mode);
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			if (mode.equals("stop")) {
				STEPS.add(new Step("boot only", () -> log("Server booted; stopping."), () -> ticks > 40, 200));
			} else {
				build();
			}
			running = true;
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (running && !done) tick();
		});
	}

	private static void log(String line) {
		REPORT.add(line);
		PvpDuelsMod.LOGGER.info("[selftest] {}", line);
	}

	private static void check(String what, boolean ok) {
		check(what, ok, "");
	}

	private static void check(String what, boolean ok, String detail) {
		if (!ok) failures++;
		log((ok ? "PASS  " : "FAIL  ") + what + (detail.isEmpty() ? "" : "   (" + detail + ")"));
	}

	private static void finish() {
		if (done) return;
		done = true;
		log(failures == 0 ? "RESULT: PASS" : "RESULT: FAIL (" + failures + " failed checks)");
		try {
			Files.write(server.getServerDirectory().resolve("selftest-result.txt"), REPORT);
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.error("Could not write the selftest result", e);
		}
		server.halt(false);
	}

	private static void step(String name, Runnable action) {
		STEPS.add(new Step(name, action, null, 0));
	}

	private static void step(String name, Runnable action, BooleanSupplier until, int timeout) {
		STEPS.add(new Step(name, action, until, timeout));
	}

	private static void tick() {
		ticks++;
		if (ticks > 20 * 400) {
			check("whole test finishes in time", false);
			finish();
			return;
		}
		while (!done) {
			if (index >= 0) {
				Step current = STEPS.get(index);
				boolean ready;
				try {
					ready = current.until == null || current.until.getAsBoolean();
				} catch (RuntimeException e) {
					check("step '" + current.name + "' wait condition", false, e.toString());
					finish();
					return;
				}
				if (!ready) {
					waited++;
					if (waited > current.timeout) {
						check("step '" + current.name + "' completes", false, "timed out after " + current.timeout + " ticks");
						finish();
					}
					return;
				}
			}
			index++;
			waited = 0;
			if (index >= STEPS.size()) {
				finish();
				return;
			}
			Step next = STEPS.get(index);
			log("--- " + next.name);
			try {
				next.action.run();
			} catch (RuntimeException | Error e) {
				PvpDuelsMod.LOGGER.error("selftest step failed", e);
				check("step '" + next.name + "' runs without errors", false, e.toString());
				finish();
				return;
			}
			if (next.until != null) return; // wait for the condition on the next tick
		}
	}

	// ------------------------------------------------------------------ helpers

	private static Bot a;
	private static Bot b;
	private static final double[] aPos = new double[3];
	private static final double[] bPos = new double[3];

	private static void run(Bot who, String command) {
		server.getCommands().performPrefixedCommand(who.createCommandSourceStack(), command);
	}

	private static Duel duel(Bot who) {
		return DuelManager.duelOf(who.getUUID());
	}

	/** Right-clicks the block with this item in the main hand, like a real client does. */
	private static void useOn(Bot who, ItemStack stack, BlockHitResult hit) {
		who.setItemInHand(InteractionHand.MAIN_HAND, stack);
		who.gameMode.useItemOn(who, (ServerLevel) who.level(), who.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
	}

	private static void hit(Bot attacker, Bot victim) {
		victim.markLoaded();
		victim.invulnerableTime = 0;
		victim.hurtServer((ServerLevel) victim.level(), attacker.damageSources().playerAttack(attacker), 1000f);
	}

	private static boolean clickMenu(Bot who, String name) {
		AbstractContainerMenu menu = who.containerMenu;
		if (!(menu instanceof ChestMenu chest)) return false;
		for (int i = 0; i < chest.getRowCount() * 9; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (!stack.isEmpty() && stack.getHoverName().getString().equals(name)) {
				menu.clicked(i, 0, ContainerInput.PICKUP, who);
				return true;
			}
		}
		return false;
	}

	private static boolean menuStep(Bot who, String name) {
		boolean ok = clickMenu(who, name);
		check("menu has the option '" + name + "'", ok);
		return ok;
	}

	private static double dist(Bot p, double[] pos) {
		return Math.sqrt(p.distanceToSqr(pos[0], pos[1], pos[2]));
	}

	private static boolean phaseIs(Bot who, Duel.Phase phase) {
		Duel d = duel(who);
		return d != null && d.phase == phase;
	}

	private static boolean noDuel(Bot who) {
		return duel(who) == null;
	}

	private static String where(Bot p) {
		return p.level().dimension().identifier() + " " + String.format("%.1f %.1f %.1f", p.getX(), p.getY(), p.getZ());
	}

	private static void invite(Bot from, Bot to, DuelConfig config) {
		DuelManager.sendInvite(from, to, from.getUUID(), config, false);
		run(to, "accept " + from.getName().getString());
	}

	// ------------------------------------------------------------------ the test

	private static void build() {
		step("the arena dimension exists", () -> {
			StringBuilder keys = new StringBuilder();
			for (ServerLevel l : server.getAllLevels()) keys.append(l.dimension().identifier()).append(' ');
			log("loaded dimensions: " + keys);
			check("dimension pvpduels:void_arena is loaded in this (already existing) world", Arena.level(server) != null);
			if (Arena.level(server) == null) finish();
		});

		step("create two fake players", () -> {
			a = spawn("Alice");
			b = spawn("Bob");
			check("both fake players are in the player list",
				server.getPlayerList().getPlayer(a.getUUID()) == a && server.getPlayerList().getPlayer(b.getUUID()) == b);
		}, () -> ticks > 60, 300);

		step("prepare Alice's kit and the marker items", () -> {
			a.getInventory().clearContent();
			b.getInventory().clearContent();
			a.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			a.getInventory().setItem(1, new ItemStack(Items.GOLDEN_APPLE, 16));
			a.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
			a.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
			run(a, "kit save testkit");
			check("/kit save created the kit", KitStore.exists(a.getUUID(), "testkit"));

			a.getInventory().clearContent();
			a.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
			a.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
			a.getInventory().setItem(21, new ItemStack(Items.STONE, 3));
			b.getInventory().setItem(20, new ItemStack(Items.DIRT, 5));
			b.setGameMode(GameType.CREATIVE);
			aPos[0] = a.getX(); aPos[1] = a.getY(); aPos[2] = a.getZ();
			bPos[0] = b.getX(); bPos[1] = b.getY(); bPos[2] = b.getZ();
			log("Alice starts at " + where(a) + ", Bob at " + where(b));
		});

		step("/duel menu flow", () -> {
			run(a, "duel Bob");
			if (!menuStep(a, "testkit")) return;
		});
		step("menu: arena", () -> { menuStep(a, "Flat void arena"); });
		step("menu: version", () -> { menuStep(a, "With walls"); });
		step("menu: mode", () -> { menuStep(a, "First to N points"); });
		step("menu: amount", () -> { menuStep(a, "First to 2"); });
		step("menu: confirm and send", () -> {
			menuStep(a, "Send the challenge");
			check("Bob got the invitation", DuelManager.pendingFrom(b.getUUID()).contains("Alice"));
			check("the invitation text has the clickable buttons", b.heard("[ACCEPT]") && b.heard("challenged you"));
		});

		step("Bob accepts with /accept", () -> {
			run(b, "accept Alice");
			Duel d = duel(a);
			check("a duel exists for Alice and Bob", d != null && duel(b) == d);
			check("both players are in the arena dimension",
				a.level().dimension().equals(Arena.DIMENSION) && b.level().dimension().equals(Arena.DIMENSION),
				where(a) + " / " + where(b));
			check("Bob is in survival now", b.gameMode() == GameType.SURVIVAL);
			check("backups were written to disk", PlayerBackup.exists(a.getUUID()) && PlayerBackup.exists(b.getUUID()));
			check("Bob received Alice's kit sword", b.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
			check("Bob received the kit armor", b.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE));
			check("Bob received the kit offhand item", b.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD));
			check("Alice's old marker item is gone during the duel", a.getInventory().getItem(21).isEmpty());
			check("Bob's old marker item is gone during the duel", b.getInventory().getItem(20).isEmpty());
			ServerLevel arena = Arena.level(server);
			int cx = Arena.centerX(d.slot);
			check("arena floor is bedrock", arena.getBlockState(new BlockPos(cx, Arena.FLOOR_Y, 0)).is(Blocks.BEDROCK));
			check("arena has walls in the 'with walls' version",
				arena.getBlockState(new BlockPos(cx + Arena.RADIUS + 1, Arena.FLOOR_Y + 5, 0)).is(Blocks.BEDROCK));
			check("there is void outside the floor", arena.getBlockState(new BlockPos(cx + 15, Arena.FLOOR_Y, 0)).isAir());
			check("config is 'first to 2' with walls", d.config.mode() == WinMode.FIRST_TO && d.config.limit() == 2 && d.config.walls());
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);

		step("rules while fighting", () -> {
			Duel d = duel(a);
			ServerLevel arena = Arena.level(server);
			int cx = Arena.centerX(d.slot);
			BlockPos floor = new BlockPos(cx, Arena.FLOOR_Y, 0);
			Vec3 top = new Vec3(cx + 0.5, Arena.FLOOR_Y + 1, 0.5);
			BlockHitResult hit = new BlockHitResult(top, Direction.UP, floor, false);

			ItemStack sword = a.getItemInHand(InteractionHand.MAIN_HAND).copy();
			useOn(a, new ItemStack(Items.STONE), hit);
			check("placing a normal block is refused", arena.getBlockState(floor.above()).isAir(), String.valueOf(arena.getBlockState(floor.above())));
			useOn(a, new ItemStack(Items.TNT), hit);
			check("placing TNT is refused", arena.getBlockState(floor.above()).isAir());
			useOn(a, new ItemStack(Items.WATER_BUCKET), hit);
			check("placing water is refused", arena.getBlockState(floor.above()).isAir() && arena.getFluidState(floor.above()).isEmpty());
			useOn(a, new ItemStack(Items.CHEST), hit);
			check("placing a chest (block entity) is allowed", arena.getBlockState(floor.above()).is(Blocks.CHEST),
				String.valueOf(arena.getBlockState(floor.above())));
			boolean broke = a.gameMode.destroyBlock(floor);
			check("breaking the bedrock floor is refused", arena.getBlockState(floor).is(Blocks.BEDROCK) && !broke);
			boolean brokeChest = a.gameMode.destroyBlock(floor.above());
			check("breaking the placed chest is allowed", brokeChest && arena.getBlockState(floor.above()).isAir());
			// leave a chest behind: the next round must clean it
			useOn(a, new ItemStack(Items.CHEST), hit);
			a.setItemInHand(InteractionHand.MAIN_HAND, sword);

			a.getInventory().setItem(5, new ItemStack(Items.DIRT, 7));
			run(a, "kit load testkit");
			check("/kit load is blocked during a duel", a.getInventory().getItem(5).is(Items.DIRT) && a.heard("can't change kits"));
			boolean quick = KitCommands.loadKit(server, a, "testkit");
			check("loadKit() (Quick kit keys) returns false during a duel", !quick);
			a.getInventory().setItem(5, ItemStack.EMPTY);
		});

		step("round 1: Alice kills Bob", () -> {
			hit(a, b);
			Duel d = duel(a);
			check("round ended, Alice has 1 point", d.phase == Duel.Phase.ROUND_END && d.points[0] == 1 && d.points[1] == 0,
				d.phase + " " + d.points[0] + "-" + d.points[1]);
			check("Bob did not really die", b.isAlive() && b.getHealth() >= b.getMaxHealth() - 0.01f, "health=" + b.getHealth());
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);

		step("round 2 started clean", () -> {
			Duel d = duel(a);
			ServerLevel arena = Arena.level(server);
			int cx = Arena.centerX(d.slot);
			check("this is round 2", d.round == 2, "round=" + d.round);
			check("the chest left in round 1 was removed", arena.getBlockState(new BlockPos(cx, Arena.FLOOR_Y + 1, 0)).isAir());
			check("both players are back at their spawn", a.distanceToSqr(Arena.spawn(d.slot, 0)[0], Arena.spawn(d.slot, 0)[1], Arena.spawn(d.slot, 0)[2]) < 4
				&& b.distanceToSqr(Arena.spawn(d.slot, 1)[0], Arena.spawn(d.slot, 1)[1], Arena.spawn(d.slot, 1)[2]) < 4,
				where(a) + " / " + where(b));
			check("both have full kits again", a.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && b.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
			// Bob falls into the void
			b.teleportTo(arena, cx + 0.5, Arena.FALL_Y - 10, 0.5, java.util.Set.of(), 0f, 0f, true);
		}, () -> phaseIs(a, Duel.Phase.ROUND_END), 100);
		step("round 2: Bob falls into the void", () -> {
			Duel d = duel(a);
			check("the void fall gave Alice the 2nd point", d.points[0] == 2 && d.points[1] == 0,
				d.phase + " " + d.points[0] + "-" + d.points[1]);
		}, () -> phaseIs(a, Duel.Phase.ENDED), 300);

		step("duel ends and everybody gets their stuff back", () -> {
			Duel d = duel(a);
			check("winner is Alice", d.winner == 0, "winner=" + d.winner);
			check("Alice was told about the rematch", a.heard("/again"));
		}, () -> noDuel(a) && noDuel(b), 600);
		step("check restored state", () -> {
			check("Alice is back in the overworld near where she was", a.level().dimension().equals(server.overworld().dimension()) && dist(a, aPos) < 1.5, where(a));
			check("Bob is back in the overworld near where he was", b.level().dimension().equals(server.overworld().dimension()) && dist(b, bPos) < 1.5, where(b));
			check("Alice has her old items back", a.getInventory().getItem(21).is(Items.STONE) && a.getInventory().getItem(21).getCount() == 3);
			check("Alice's kit items are gone", a.getInventory().getItem(0).isEmpty() && a.getItemBySlot(EquipmentSlot.CHEST).isEmpty());
			check("Bob has his old items back", b.getInventory().getItem(20).is(Items.DIRT) && b.getInventory().getItem(20).getCount() == 5);
			check("Bob's kit items are gone", b.getInventory().getItem(0).isEmpty() && b.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
				&& b.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty());
			check("Bob's game mode is back to creative", b.gameMode() == GameType.CREATIVE);
			check("the night vision effect is gone", !a.hasEffect(MobEffects.NIGHT_VISION) && !b.hasEffect(MobEffects.NIGHT_VISION));
			check("backups are deleted", !PlayerBackup.exists(a.getUUID()) && !PlayerBackup.exists(b.getUUID()));
		});

		step("/again and /leave", () -> {
			run(a, "again");
			check("/again sent a rematch invitation", DuelManager.pendingFrom(b.getUUID()).contains("Alice"));
			run(b, "accept");
			check("rematch started", duel(a) != null && duel(a) == duel(b));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("Alice types /leave", () -> {
			run(a, "leave");
			check("Alice got her inventory back right away", a.level().dimension().equals(server.overworld().dimension())
				&& a.getInventory().getItem(21).is(Items.STONE), where(a));
			Duel d = duel(b);
			check("Bob is declared winner by forfeit", d != null && d.phase == Duel.Phase.ENDED && d.winner == 1);
		}, () -> noDuel(b), 600);
		step("Bob restored after the forfeit", () -> {
			check("Bob is back in the overworld with his items", b.level().dimension().equals(server.overworld().dimension())
				&& b.getInventory().getItem(20).is(Items.DIRT), where(b));
		});

		step("timed duel with the no-contact rule", () -> {
			Duel.ticksPerMinute = 400;
			Duel.noContactLimit = 80;
			Duel.noContactWarning = 20;
			invite(a, b, new DuelConfig("testkit", false, WinMode.TIMED, 1));
			check("timed duel started", duel(a) != null);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("nobody fights: one player is teleported next to the other", () -> {
			check("players start far apart", a.distanceTo(b) > 10, "distance=" + a.distanceTo(b));
		}, () -> a.distanceTo(b) < 5, 200);
		step("the no-contact teleport kept both at full health", () -> {
			check("nobody lost health", a.getHealth() >= a.getMaxHealth() - 0.01f && b.getHealth() >= b.getMaxHealth() - 0.01f);
			hit(a, b); // 1-0 for Alice before time is up
			Duel d = duel(a);
			check("Alice leads 1-0", d.points[0] == 1 && d.points[1] == 0 && !d.timedOut, d.points[0] + "-" + d.points[1] + " timedOut=" + d.timedOut);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("wait for the time limit", () -> {}, () -> {
			Duel d = duel(a);
			return d != null && d.timedOut;
		}, 600);
		step("time is up: Bob equalises, tiebreak round", () -> {
			Duel d = duel(a);
			if (d.phase != Duel.Phase.FIGHTING) {
				check("duel is fighting when time is up", false, d.phase.toString());
				return;
			}
			hit(b, a);
			check("1-1 after time is up: no winner, tiebreak round", d.points[0] == 1 && d.points[1] == 1 && d.tiebreak && d.winner == -2,
				d.points[0] + "-" + d.points[1] + " tiebreak=" + d.tiebreak + " winner=" + d.winner);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("tiebreak round decides", () -> {
			hit(a, b);
			Duel d = duel(a);
			check("Alice wins the tiebreak, 2-1", d.points[0] == 2 && d.points[1] == 1, d.points[0] + "-" + d.points[1]);
		}, () -> phaseIs(a, Duel.Phase.ENDED), 400);
		step("timed duel closes", () -> {
			Duel.ticksPerMinute = 60 * 20;
			Duel.noContactLimit = 5 * 60 * 20;
			Duel.noContactWarning = 5 * 20;
		}, () -> noDuel(a) && noDuel(b), 600);

		step("disconnect in the middle of a duel", () -> {
			check("Bob is in the overworld before", b.level().dimension().equals(server.overworld().dimension()), where(b));
			invite(a, b, new DuelConfig("testkit", false, WinMode.ROUNDS, 3));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("Bob's connection drops", () -> {
			b.connection.onDisconnect(new DisconnectionDetails(Component.literal("selftest disconnect")));
		}, () -> phaseIs(a, Duel.Phase.ENDED), 100);
		step("after the disconnect", () -> {
			Duel d = duel(a);
			check("Alice wins by forfeit", d != null && d.winner == 0, d == null ? "no duel" : d.phase + " winner=" + d.winner);
			check("Bob's backup stays on disk", PlayerBackup.exists(b.getUUID()));
		}, () -> noDuel(a), 600);
		step("Bob joins again", () -> {
			check("Bob's backup is still there after the duel closed", PlayerBackup.exists(b.getUUID()));
			b = spawn("Bob");
		}, () -> !PlayerBackup.exists(b.getUUID()), 200);
		step("Bob got everything back on joining", () -> {
			check("Bob is in the overworld with his items", b.level().dimension().equals(server.overworld().dimension())
				&& b.getInventory().getItem(20).is(Items.DIRT) && b.getInventory().getItem(0).isEmpty(), where(b));
			check("Bob heard that his inventory was restored", b.heard("were restored"));
		});

		step("unlimited duel", () -> {
			invite(a, b, new DuelConfig("testkit", false, WinMode.ROUNDS, 0));
			check("unlimited duel started", duel(a) != null && duel(a).config.unlimited());
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("unlimited: Alice scores, the duel goes on", () -> {
			hit(a, b);
			Duel d = duel(a);
			check("the duel is not decided after one round", d.phase == Duel.Phase.ROUND_END && d.winner == -2, d.phase + " winner=" + d.winner);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("unlimited: Bob leaves", () -> {
			run(b, "leave");
			Duel d = duel(a);
			check("leaving an unlimited duel is decided by score (Alice ahead)", d != null && d.winner == 0 && d.phase == Duel.Phase.ENDED,
				d == null ? "no duel" : d.phase + " winner=" + d.winner);
		}, () -> noDuel(a) && noDuel(b), 600);

		step("sanity: commands from outside a duel", () -> {
			run(a, "leave");
			check("/leave outside a duel gives an error", a.heard("not in a duel"));
			run(a, "duel Alice");
			check("you can't duel yourself", a.heard("can't duel yourself"));
		});
	}
}
