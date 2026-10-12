package com.pvpduels.duel;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.GuideBook;
import com.pvpduels.duel.TestDummy.Dummy;
import com.pvpduels.stats.StatsStore;
import com.pvpduels.workshop.Workshop;
import com.pvpduels.kit.KitCommands;
import com.pvpduels.kit.KitStore;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

	private static Dummy spawn(String name) {
		return TestDummy.create(server, name);
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

	private static ItemStack enchantedSword = ItemStack.EMPTY;
	private static int dummyDuelsBefore;
	private static Dummy a;
	private static Dummy b;
	private static final double[] aPos = new double[3];
	private static final double[] bPos = new double[3];

	private static int circleCx;

	private static BlockPos at(int x, int y, int z) {
		return new BlockPos(x, y, z);
	}

	private static boolean isBlock(int x, int y, int z, net.minecraft.world.level.block.Block block) {
		return Terrain.level(server).getBlockState(at(x, y, z)).is(block);
	}

	private static void run(Dummy who, String command) {
		server.getCommands().performPrefixedCommand(who.createCommandSourceStack(), command);
	}

	private static Duel duel(Dummy who) {
		return DuelManager.duelOf(who.getUUID());
	}

	/** Right-clicks the block with this item in the main hand, like a real client does. */
	private static void useOn(Dummy who, ItemStack stack, BlockHitResult hit) {
		who.setItemInHand(InteractionHand.MAIN_HAND, stack);
		who.gameMode.useItemOn(who, (ServerLevel) who.level(), who.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
	}

	private static void hit(Dummy attacker, Dummy victim) {
		victim.markLoaded();
		victim.invulnerableTime = 0;
		victim.hurtServer((ServerLevel) victim.level(), attacker.damageSources().playerAttack(attacker), 1000f);
	}

	private static boolean clickMenu(Dummy who, String name) {
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

	private static boolean menuStep(Dummy who, String name) {
		boolean ok = clickMenu(who, name);
		check("menu has the option '" + name + "'", ok);
		return ok;
	}

	private static double dist(Dummy p, double[] pos) {
		return Math.sqrt(p.distanceToSqr(pos[0], pos[1], pos[2]));
	}

	private static boolean phaseIs(Dummy who, Duel.Phase phase) {
		Duel d = duel(who);
		return d != null && d.phase == phase;
	}

	private static boolean noDuel(Dummy who) {
		return duel(who) == null;
	}

	private static String where(Dummy p) {
		return p.level().dimension().identifier() + " " + String.format("%.1f %.1f %.1f", p.getX(), p.getY(), p.getZ());
	}

	/** The sidebar lines a player currently sees, rebuilt from the packets the server sent them. */
	private static List<String> sidebar(Dummy who) {
		Map<Integer, String> lines = new TreeMap<>();
		for (Packet<?> packet : List.copyOf(who.link.sent)) {
			if (packet instanceof ClientboundSetObjectivePacket obj) {
				if (obj.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD || obj.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) lines.clear();
			} else if (packet instanceof ClientboundSetScorePacket score) {
				lines.put(Integer.parseInt(score.owner().substring(5)), score.display().map(Component::getString).orElse("?"));
			} else if (packet instanceof ClientboundResetScorePacket reset) {
				lines.remove(Integer.parseInt(reset.owner().substring(5)));
			}
		}
		return new java.util.ArrayList<>(lines.values());
	}

	private static boolean sidebarHas(Dummy who, String text) {
		for (String line : sidebar(who)) if (line.contains(text)) return true;
		return false;
	}

	private static void invite(Dummy from, Dummy to, DuelConfig config) {
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
			check("dimension pvpduels:normal_arena is loaded too", Terrain.level(server) != null);
			if (Arena.level(server) == null || Terrain.level(server) == null) finish();
		});

		step("create two fake players", () -> {
			a = spawn("Alice");
			b = spawn("Bob");
			check("both fake players are in the player list",
				server.getPlayerList().getPlayer(a.getUUID()) == a && server.getPlayerList().getPlayer(b.getUUID()) == b);
			check("a new player gets the guide book", GuideBook.has(a) && GuideBook.has(b));
			check("a new player sees the sidebar with their stats", sidebarHas(a, "Wins: 0") && sidebarHas(a, "Duels played"), String.valueOf(sidebar(a)));
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
			check("the sidebar shows the duel score", sidebarHas(a, "vs Bob") && sidebarHas(a, "Alice: 1") && sidebarHas(a, "Bob: 0"), String.valueOf(sidebar(a)));
			check("the sidebar shows the win condition", sidebarHas(a, "First to 2"), String.valueOf(sidebar(a)));
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

		step("test dummy: only admins can use the command", () -> {
			run(a, "test_dummy gate");
			check("a normal player can't use /test_dummy", server.getPlayerList().getPlayerByName("gate") == null);
		});
		step("test dummy: spawn, auto-accept", () -> {
			dummyDuelsBefore = StatsStore.get(a.getUUID()).duels;
			String problem = TestDummy.spawn(a, "dum");
			Dummy dum = (Dummy) server.getPlayerList().getPlayerByName("dum");
			check("the dummy was created", problem == null && dum != null, String.valueOf(problem));
			check("a bad name is refused", TestDummy.spawn(a, "bad name!") != null);
			check("a duplicate name is refused", TestDummy.spawn(a, "dum") != null);
			check("the dummy stands next to the owner", dum != null && a.distanceTo(dum) < 5, dum == null ? "" : String.valueOf(a.distanceTo(dum)));
			DuelManager.sendInvite(a, dum, a.getUUID(), new DuelConfig("testkit", false, WinMode.FIRST_TO, 1), false);
			check("the dummy accepted the challenge by itself", duel(a) != null && duel(dum) == duel(a));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("test dummy: Alice wins", () -> {
			Dummy dum = (Dummy) server.getPlayerList().getPlayerByName("dum");
			hit(a, dum);
			Duel d = duel(a);
			check("first to 1 point: Alice beats the dummy", d.points[0] == 1 && d.winner == 0, d.points[0] + "-" + d.points[1] + " winner=" + d.winner);
		}, () -> noDuel(a), 600);
		step("test dummy: /again is accepted by the dummy", () -> {
			run(a, "again");
			check("the rematch started without anybody clicking", duel(a) != null);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("test dummy: removing it in the middle of a duel", () -> {
			int removed = TestDummy.remove(server, "dum");
			check("the dummy was removed", removed == 1 && server.getPlayerList().getPlayerByName("dum") == null);
			Duel d = duel(a);
			check("the duel ends in Alice's favour", d != null && d.winner == 0 && d.phase == Duel.Phase.ENDED,
				d == null ? "no duel" : d.phase + " winner=" + d.winner);
			check("duels against a test dummy don't count in the statistics", StatsStore.get(a.getUUID()).duels == dummyDuelsBefore,
				StatsStore.get(a.getUUID()).duels + " vs " + dummyDuelsBefore);
			check("the dummy is not auto-accepting any more", !TestDummy.accepts(java.util.UUID.nameUUIDFromBytes("OfflinePlayer:dum".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
		}, () -> noDuel(a), 600);

		step("workshop: enter", () -> {
			run(a, "kit create");
			check("Alice is in the workshop world", a.level().dimension().equals(Arena.DIMENSION) && Workshop.isIn(a.getUUID()), where(a));
			check("she is in creative mode", a.gameMode() == GameType.CREATIVE);
			check("her own items are out of the way", a.getInventory().getItem(21).isEmpty());
			check("her inventory was backed up to disk", PlayerBackup.exists(a.getUUID()));
			check("there is a stone platform under her", Arena.level(server).getBlockState(a.blockPosition().below()).is(Blocks.STONE),
				String.valueOf(Arena.level(server).getBlockState(a.blockPosition().below())));
			run(a, "kit create");
			check("she can't open the workshop twice", a.heard("already in the kit workshop"));
			DuelManager.sendInvite(b, a, b.getUUID(), new DuelConfig("testkit", false, WinMode.ROUNDS, 1), false);
			check("a duel request to somebody in the workshop is refused", b.heard("busy") && DuelManager.pendingFrom(a.getUUID()).isEmpty());
		});
		step("workshop: nothing can be placed or broken", () -> {
			ServerLevel arena = Arena.level(server);
			BlockPos floor = a.blockPosition().below();
			BlockHitResult hit = new BlockHitResult(new Vec3(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5), Direction.UP, floor, false);
			useOn(a, new ItemStack(Items.STONE), hit);
			check("placing a block is refused", arena.getBlockState(floor.above()).isAir());
			useOn(a, new ItemStack(Items.CHEST), hit);
			check("placing a chest is refused too", arena.getBlockState(floor.above()).isAir());
			boolean broke = a.gameMode.destroyBlock(floor);
			check("breaking the platform is refused", !broke && arena.getBlockState(floor).is(Blocks.STONE));
			a.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		});
		step("workshop: /enchant and /infinite", () -> {
			a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
			run(a, "enchant sharpness 255");
			String text = a.getMainHandItem().getEnchantments().toString();
			check("a normal player can enchant with level 255 in the workshop", text.contains("255"), text);
			run(a, "enchant fire_protection 3");
			text = a.getMainHandItem().getEnchantments().toString();
			check("any enchantment goes on any item", text.contains("fire_protection"), text);
			run(a, "enchant unbreaking 256");
			check("level 256 is refused", !a.getMainHandItem().getEnchantments().toString().contains("unbreaking"));
			run(a, "ench smite 7");
			check("/ench works too", a.getMainHandItem().getEnchantments().toString().contains("smite"));
			enchantedSword = a.getMainHandItem().copy();

			a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			run(a, "infinite 64");
			check("/infinite 64 on a totem", a.getMainHandItem().getCount() == 64, "count=" + a.getMainHandItem().getCount());
			run(a, "infinite 99");
			check("/infinite 99 on a totem", a.getMainHandItem().getCount() == 99, "count=" + a.getMainHandItem().getCount());
			run(a, "infinite 100");
			check("more than 99 is refused", a.getMainHandItem().getCount() == 99);
			a.getInventory().setItem(3, a.getMainHandItem().copy());
			a.setItemInHand(InteractionHand.MAIN_HAND, enchantedSword);
			run(a, "infinite 5");
			check("items with durability can't be stacked", a.getMainHandItem().getCount() == 1 && a.heard("durability"));
			a.getInventory().setItem(0, a.getMainHandItem().copy());
			run(a, "kit save wk");
			try {
				var kit = KitStore.read(server, a.getUUID(), "wk");
				check("the saved kit keeps the 99 totems", kit.isPresent() && kit.get().slots()[3].getCount() == 99 && kit.get().slots()[3].is(Items.TOTEM_OF_UNDYING),
					kit.isPresent() ? "count=" + kit.get().slots()[3].getCount() : "no kit");
				check("the saved kit keeps the enchantments", kit.isPresent() && kit.get().slots()[0].getEnchantments().toString().contains("255"));
			} catch (java.io.IOException e) {
				check("the saved kit can be read", false, e.toString());
			}
		});
		step("workshop: leave", () -> {
			run(a, "leave");
			check("Alice is back in the overworld with her own items", a.level().dimension().equals(server.overworld().dimension())
				&& a.getInventory().getItem(21).is(Items.STONE) && a.getInventory().getItem(21).getCount() == 3, where(a));
			check("her old game mode is back", a.gameMode() == GameType.SURVIVAL);
			check("the backup was deleted", !PlayerBackup.exists(a.getUUID()));
			check("the workshop kit stays saved", KitStore.exists(a.getUUID(), "wk"));
			run(a, "enchant sharpness 5");
			check("outside the workshop /enchant is refused for normal players", a.heard("only use this in the kit workshop"));
			run(a, "infinite 5");
			check("outside the workshop /infinite is refused too", a.heard("only use this in the kit workshop"));
		});
		step("workshop: edit an existing kit and leave with /kit exit", () -> {
			run(a, "kit create testkit");
			check("the kit was loaded for editing", Workshop.isIn(a.getUUID()) && a.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
			run(a, "kit exit");
			check("/kit exit leaves the workshop", !Workshop.isIn(a.getUUID()) && a.level().dimension().equals(server.overworld().dimension()));
			run(a, "kit create nokit");
			check("an unknown kit is refused", !Workshop.isIn(a.getUUID()) && a.heard("no kit named"));
		});
		step("workshop: disconnect inside it", () -> {
			run(a, "kit create");
			a.connection.onDisconnect(new DisconnectionDetails(Component.literal("selftest disconnect")));
		}, () -> !Workshop.isIn(a.getUUID()), 100);
		step("workshop: the backup stays on disk after the disconnect", () -> {
			check("the backup is still there", PlayerBackup.exists(a.getUUID()));
			a = spawn("Alice");
		}, () -> !PlayerBackup.exists(a.getUUID()), 200);
		step("workshop: joining again brings everything back", () -> {
			check("Alice is in the overworld with her items", a.level().dimension().equals(server.overworld().dimension())
				&& a.getInventory().getItem(21).is(Items.STONE), where(a));
			check("and in survival", a.gameMode() == GameType.SURVIVAL);
		});


		// ---------------------------------------------------------------- circular and unlimited arenas
		step("circular arena: menus", () -> {
			run(a, "duel Bob");
			menuStep(a, "testkit");
		});
		step("circular arena: menu arena", () -> { menuStep(a, "Circular arena"); });
		step("circular arena: menu radius", () -> { menuStep(a, "Radius 100"); });
		step("circular arena: menu mode", () -> { menuStep(a, "First to N points"); });
		step("circular arena: menu amount", () -> { menuStep(a, "First to 2"); });
		step("circular arena: send and accept", () -> {
			menuStep(a, "Send the challenge");
			check("the invitation names the circular arena", b.heard("Circular arena (radius 100)"));
			run(b, "accept Alice");
			Duel d = duel(a);
			check("a circular duel exists", d != null && d.config.arena() == ArenaKind.CIRCLE && d.config.radius() == 100);
			if (d != null) circleCx = (int) d.space.centerX();
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("circular arena: the terrain has the right layers", () -> {
			check("both players are in the normal arena dimension", a.level().dimension().equals(Terrain.DIMENSION) && b.level().dimension().equals(Terrain.DIMENSION),
				where(a) + " / " + where(b));
			check("Terrain keeps one arena open", Terrain.activeRegions() == 1, String.valueOf(Terrain.activeRegions()));
			int x = circleCx;
			check("y70 is dirt", isBlock(x, 70, 0, Blocks.DIRT));
			check("y66 is dirt (5 blocks of dirt)", isBlock(x, 66, 0, Blocks.DIRT));
			check("y65 is stone", isBlock(x, 65, 0, Blocks.STONE));
			check("y0 is stone", isBlock(x, 0, 0, Blocks.STONE));
			check("y-1 is deepslate", isBlock(x, -1, 0, Blocks.DEEPSLATE));
			check("y-59 is deepslate", isBlock(x, -59, 0, Blocks.DEEPSLATE));
			check("y-60 is bedrock", isBlock(x, -60, 0, Blocks.BEDROCK));
			check("y-61 is air", isBlock(x, -61, 0, Blocks.AIR));
			check("y71 is air", isBlock(x, 71, 0, Blocks.AIR));
			check("the players stand on the dirt", Math.abs(a.getY() - 71) < 0.5 && Math.abs(b.getY() - 71) < 0.5, where(a) + " / " + where(b));
			check("they are 32 blocks apart", Math.abs(a.distanceTo(b) - 32) < 1.5, String.valueOf(a.distanceTo(b)));
			Terrain.level(server).getChunk((x + 100) >> 4, 0);
			Terrain.level(server).getChunk((x - 100) >> 4, 0);
			Terrain.level(server).getChunk(x >> 4, 100 >> 4);
			Terrain.level(server).getChunk((x + 71) >> 4, 71 >> 4);
		});
		step("circular arena: the barrier ring is drawn as its chunks load", () -> {}, () ->
			isBlock(circleCx + 100, 100, 0, Blocks.BARRIER) && isBlock(circleCx - 100, 100, 0, Blocks.BARRIER)
				&& isBlock(circleCx, 100, 100, Blocks.BARRIER) && isBlock(circleCx + 71, 100, 71, Blocks.BARRIER), 200);
		step("circular arena: the ring has the right shape", () -> {
			int x = circleCx;
			check("the ring is closed right at the radius", isBlock(x + 100, 100, 0, Blocks.BARRIER) && isBlock(x - 100, 100, 0, Blocks.BARRIER) && isBlock(x, 100, 100, Blocks.BARRIER));
			check("inside the ring is free", isBlock(x + 99, 100, 0, Blocks.AIR) && isBlock(x + 98, 71, 0, Blocks.AIR));
			check("the ring goes down to the bedrock layer", isBlock(x + 100, -60, 0, Blocks.BARRIER) && isBlock(x + 100, 30, 0, Blocks.BARRIER));
			check("the ring goes up to the build limit", isBlock(x + 100, 319, 0, Blocks.BARRIER));
			check("nothing below the bedrock layer", isBlock(x + 100, -61, 0, Blocks.AIR));
			check("the ring is also there on the diagonal", isBlock(x + 71, 100, 71, Blocks.BARRIER));
			boolean blocked = false;
			for (int i = 0; i < 70; i++) {
				// walking along the diagonal from the center, the first non-air block must be a barrier (no gap to slip through)
				if (!isBlock(x + i, 100, i, Blocks.AIR)) { blocked = true; break; }
			}
			check("the diagonal inside the circle is free", !blocked);
		});
		step("circular arena: normal blocks can be placed and dug", () -> {
			ServerLevel lvl = Terrain.level(server);
			BlockPos ground = a.blockPosition().below();
			ItemStack sword = a.getItemInHand(InteractionHand.MAIN_HAND).copy();
			BlockPos stoneBase = ground.east();
			BlockPos chestBase = ground.east(2);
			useOn(a, new ItemStack(Items.STONE), new BlockHitResult(new Vec3(stoneBase.getX() + 0.5, stoneBase.getY() + 1, stoneBase.getZ() + 0.5), Direction.UP, stoneBase, false));
			check("a stone block can be placed", lvl.getBlockState(stoneBase.above()).is(Blocks.STONE), String.valueOf(lvl.getBlockState(stoneBase.above())));
			useOn(a, new ItemStack(Items.CHEST), new BlockHitResult(new Vec3(chestBase.getX() + 0.5, chestBase.getY() + 1, chestBase.getZ() + 0.5), Direction.UP, chestBase, false));
			check("a chest can be placed", lvl.getBlockState(chestBase.above()).is(Blocks.CHEST));
			a.setItemInHand(InteractionHand.MAIN_HAND, sword);
			boolean dug = a.gameMode.destroyBlock(ground.south());
			check("the ground can be dug", dug && lvl.getBlockState(ground.south()).isAir());
			lvl.setBlock(at(circleCx - 5, 40, 0), Blocks.OBSIDIAN.defaultBlockState(), 2);
			lvl.setBlock(at(circleCx + 30, 71, 3), Blocks.TNT.defaultBlockState(), 2);
			check("the tracked changes exist", lvl.getBlockState(at(circleCx - 5, 40, 0)).is(Blocks.OBSIDIAN));
			hit(a, b);
			Duel d = duel(a);
			check("round 1 went to Alice", d.points[0] == 1 && d.phase == Duel.Phase.ROUND_END, d.points[0] + "-" + d.points[1] + " " + d.phase);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("circular arena: round 2 started on clean terrain", () -> {
			int x = circleCx;
			check("the stone block is gone", isBlock(circleCx - 15, 71, 0, Blocks.AIR), String.valueOf(Terrain.level(server).getBlockState(at(circleCx - 15, 71, 0))));
			check("the dug hole is filled again", isBlock(circleCx - 16, 70, 1, Blocks.DIRT), String.valueOf(Terrain.level(server).getBlockState(at(circleCx - 16, 70, 1))));
			check("the obsidian inside the ground is gone", isBlock(x - 5, 40, 0, Blocks.STONE));
			check("the TNT on the surface is gone", isBlock(x + 30, 71, 3, Blocks.AIR));
			check("the chest is gone", isBlock(circleCx - 14, 71, 0, Blocks.AIR));
			check("the ring is still there", isBlock(x + 100, 100, 0, Blocks.BARRIER));
			check("round 2 is on", duel(a).round == 2);
		});
		step("circular arena: leaving the circle loses the round", () -> {
			ServerLevel lvl = Terrain.level(server);
			b.teleportTo(lvl, circleCx + 110.5, 71, 0.5, java.util.Set.of(), 0f, 0f, true);
		}, () -> phaseIs(a, Duel.Phase.ENDED), 300);
		step("circular arena: Alice won and the arena closes", () -> {
			Duel d = duel(a);
			check("Alice won 2-0", d != null && d.winner == 0 && d.points[0] == 2, d == null ? "no duel" : d.winner + " " + d.points[0] + "-" + d.points[1]);
			check("Alice was told that Bob left the arena", a.heard("Bob left the arena"));
		}, () -> noDuel(a) && noDuel(b), 600);
		step("circular arena: closed", () -> {
			check("Terrain has no open arena any more", Terrain.activeRegions() == 0, String.valueOf(Terrain.activeRegions()));
			check("both players are back in the overworld", a.level().dimension().equals(server.overworld().dimension())
				&& b.level().dimension().equals(server.overworld().dimension()), where(a) + " / " + where(b));
			var chunk = Terrain.level(server).getChunkSource().getChunkNow((circleCx + 100) >> 4, 0);
			check("the ring is removed from chunks that are still loaded", chunk == null || isBlock(circleCx + 100, 100, 0, Blocks.AIR));
		});

		step("unlimited arena: start", () -> {
			Duel.farCountdown = 40;
			invite(a, b, new DuelConfig("testkit", ArenaKind.UNLIMITED, false, 0, WinMode.FIRST_TO, 3));
			Duel d = duel(a);
			check("an unlimited duel exists", d != null && d.config.arena() == ArenaKind.UNLIMITED && d.config.arenaName().equals("Unlimited arena"));
			if (d != null) circleCx = (int) d.space.centerX();
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("unlimited arena: players are in the normal terrain far from the other arenas", () -> {
			check("both are in the normal arena dimension", a.level().dimension().equals(Terrain.DIMENSION) && b.level().dimension().equals(Terrain.DIMENSION));
			check("the arena is centered at x=" + circleCx, circleCx >= Terrain.UNLIMITED_BASE && Math.abs(a.getX() - circleCx) < 20, where(a));
			check("no barrier ring here", isBlock(circleCx + 100, 100, 0, Blocks.AIR));
			// Bob runs away 150 blocks
			b.teleportTo(Terrain.level(server), circleCx + 150.5, 71, 0.5, java.util.Set.of(), 0f, 0f, true);
			check("Bob is farther than 100 blocks from Alice", a.distanceTo(b) > 100, String.valueOf(a.distanceTo(b)));
		});
		step("unlimited arena: the 100 block rule fires", () -> {}, () -> a.distanceTo(b) < 10, 300);
		step("unlimited arena: Bob lost half of his health, Alice is untouched", () -> {
			check("Bob has about half of his health", b.getHealth() > 8f && b.getHealth() < 12f, "health=" + b.getHealth());
			check("Alice kept all her health", a.getHealth() >= a.getMaxHealth() - 0.01f, "health=" + a.getHealth());
			check("they were told", a.heard("went too far") && b.heard("more than 100 blocks"));
			check("the duel goes on and nobody lost a round", duel(a).phase == Duel.Phase.FIGHTING && duel(a).points[0] == 0 && duel(a).points[1] == 0);
			Duel.farCountdown = 5 * 20;
			run(b, "leave");
			run(a, "leave");
		}, () -> noDuel(a) && noDuel(b) && Terrain.activeRegions() == 0, 600);
		step("unlimited arena: nothing left open", () -> {
			check("Terrain has no open arena", Terrain.activeRegions() == 0, String.valueOf(Terrain.activeRegions()));
			check("both are back in the overworld", a.level().dimension().equals(server.overworld().dimension())
				&& b.level().dimension().equals(server.overworld().dimension()), where(a) + " / " + where(b));
		});

		step("statistics, sidebar and guide book", () -> {
			StatsStore.Stats sa = StatsStore.get(a.getUUID());
			StatsStore.Stats sb = StatsStore.get(b.getUUID());
			check("Alice's wins and losses were counted", sa.wins >= 3 && sa.losses >= 1 && sa.duels == sa.wins + sa.losses + sa.draws,
				sa.wins + "W " + sa.losses + "L of " + sa.duels);
			check("Bob's wins were counted", sb.wins >= 1 && sb.losses >= 1, sb.wins + "W " + sb.losses + "L");
			check("the best streak is at least the streak", sa.bestStreak >= sa.streak && sa.bestStreak >= 1);
			run(a, "sidebar off");
			check("/sidebar off hides it", sidebar(a).isEmpty());
			run(a, "sidebar on");
			check("/sidebar on shows her stats again", sidebarHas(a, "Wins: " + sa.wins), String.valueOf(sidebar(a)));
			run(a, "sidebar");
			check("/sidebar alone toggles", sidebar(a).isEmpty());
			run(a, "sidebar");
			check("and toggles back", !sidebar(a).isEmpty());

			a.getInventory().clearContent();
			run(a, "guide book");
			check("/guide book gives the guide book", GuideBook.has(a));
			a.getInventory().setItem(5, new ItemStack(Items.DIRT, 2));
			run(a, "kit save withbook");
			try {
				var kit = KitStore.read(server, a.getUUID(), "withbook");
				boolean noBook = kit.isPresent();
				boolean hasDirt = false;
				if (kit.isPresent()) {
					for (ItemStack stack : kit.get().slots()) {
						if (GuideBook.isGuide(stack)) noBook = false;
					}
					hasDirt = kit.get().slots()[5].is(Items.DIRT);
				}
				check("a saved kit never contains the guide book (but keeps other items)", noBook && hasDirt);
			} catch (java.io.IOException e) {
				check("the kit can be read", false, e.toString());
			}
			a.getInventory().clearContent();
			a.getInventory().setItem(21, new ItemStack(Items.STONE, 3));
		});

		step("sanity: commands from outside a duel", () -> {
			run(a, "leave");
			check("/leave outside a duel gives an error", a.heard("not in a duel"));
			run(a, "duel Alice");
			check("you can't duel yourself", a.heard("can't duel yourself"));
		});
		step("shutdown while somebody is in the workshop", () -> {
			run(a, "kit create");
			check("Alice is in the workshop when the server stops", Workshop.isIn(a.getUUID()));
		});
	}
}
