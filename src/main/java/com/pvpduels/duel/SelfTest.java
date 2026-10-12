package com.pvpduels.duel;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.GuideBook;
import com.pvpduels.duel.TestDummy.Dummy;
import com.pvpduels.stats.StatsStore;
import com.pvpduels.workshop.Workshop;
import com.pvpduels.kit.KitCommands;
import com.pvpduels.kit.KitStore;
import com.pvpduels.util.Prefs;
import com.pvpduels.kit.PublicKits;
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
			Duel.traceEnd = true; // every end of a duel is logged with where it came from
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
		if (ticks > 20 * 600) {
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
	private static int ruleStartTick;
	private static float ruleStartHealth;
	private static int carlTick;
	private static Dummy carl;
	private static Dummy dora;
	private static Dummy bot;
	private static int botTick;
	private static int botStatsBefore;
	private static int botEloBefore;
	private static double botX;

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

	/** A hit that does not kill (the armor of the kit makes it a bit smaller). */
	private static void softHit(Dummy attacker, Dummy victim, float amount) {
		victim.markLoaded();
		victim.invulnerableTime = 0;
		victim.hurtServer((ServerLevel) victim.level(), attacker.damageSources().playerAttack(attacker), amount);
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

	private static boolean menuHas(Dummy who, String name) {
		AbstractContainerMenu menu = who.containerMenu;
		if (!(menu instanceof ChestMenu chest)) return false;
		for (int i = 0; i < chest.getRowCount() * 9; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (!stack.isEmpty() && stack.getHoverName().getString().equals(name)) return true;
		}
		return false;
	}

	/**
	 * What the game of this (fake) player would show in its inventory, rebuilt only from the packets the server sent it:
	 * the last full content of its own inventory, then every single-slot update after that. Index = inventory slot
	 * (0-8 hotbar, 9-35 main, 36-39 boots..helmet, 40 off hand). Null when no full content was ever sent.
	 */
	private static ItemStack[] clientView(Dummy who) {
		java.util.List<net.minecraft.network.protocol.Packet<?>> sent = who.link.sent;
		int start = -1;
		for (int i = sent.size() - 1; i >= 0; i--) {
			if (sent.get(i) instanceof net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket c && c.containerId() == 0) {
				start = i;
				break;
			}
		}
		if (start < 0) return null;
		ItemStack[] view = new ItemStack[41];
		java.util.Arrays.fill(view, ItemStack.EMPTY);
		var full = (net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket) sent.get(start);
		for (int menu = 0; menu < full.items().size(); menu++) {
			int idx = menuToInventory(menu);
			if (idx >= 0) view[idx] = full.items().get(menu);
		}
		for (int i = start + 1; i < sent.size(); i++) {
			var packet = sent.get(i);
			if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket one && one.getContainerId() == 0) {
				int idx = menuToInventory(one.getSlot());
				if (idx >= 0) view[idx] = one.getItem();
			} else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket one) {
				if (one.slot() >= 0 && one.slot() < 41) view[one.slot()] = one.contents();
			} else if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket again && again.containerId() == 0) {
				for (int menu = 0; menu < again.items().size(); menu++) {
					int idx = menuToInventory(menu);
					if (idx >= 0) view[idx] = again.items().get(menu);
				}
			}
		}
		return view;
	}

	/** Slot numbers of the player's own inventory screen to the index in the inventory (-1: crafting grid and so on). */
	private static int menuToInventory(int menu) {
		if (menu >= 9 && menu <= 35) return menu;
		if (menu >= 36 && menu <= 44) return menu - 36;
		if (menu >= 5 && menu <= 8) return 44 - menu; // 5 helmet -> 39 ... 8 boots -> 36
		if (menu == 45) return 40;
		return -1;
	}

	private static boolean clientMatches(Dummy who) {
		return clientDiff(who).isEmpty();
	}

	/** Empty when the rebuilt client view equals the real inventory, else the first differences. */
	private static String clientDiff(Dummy who) {
		ItemStack[] view = clientView(who);
		if (view == null) return "the server never sent the full inventory";
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < 41; i++) {
			ItemStack real = who.getInventory().getItem(i);
			if (!ItemStack.matches(real, view[i])) {
				if (out.length() < 200) out.append("slot ").append(i).append(": server ").append(real).append(" / game ").append(view[i]).append("; ");
			}
		}
		return out.toString();
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
		step("menu: rules", () -> { menuStep(a, "Continue"); });
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
			check("her game was sent the whole kit (what a real client would show)", clientMatches(a), clientDiff(a));
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
			check("Alice was told about the rematch", a.heard("[REMATCH]"));
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
		step("circular arena: menu rules", () -> { menuStep(a, "Continue"); });
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


		// ---------------------------------------------------------------- sharing kits
		step("sharing: /publicate and /kit public", () -> {
			run(a, "publicate nothere");
			check("an unknown kit can't be published", PublicKits.find("nothere").isEmpty() && a.heard("don't have a kit"));
			run(a, "publicate testkit");
			var entry = PublicKits.find("testkit");
			check("the kit is on the public shelf", entry.isPresent() && entry.get().owner().equals(a.getUUID()) && entry.get().ownerName().equals("Alice"));
			check("the other players hear about it", b.heard("shared the kit 'testkit'"));
			run(b, "kit public");
			check("/kit public lists the kit and its author", b.heard("testkit") && b.heard("by Alice"));
			run(b, "unpublish testkit");
			check("only the owner can unpublish", PublicKits.find("testkit").isPresent() && b.heard("Only Alice can unpublish"));
		});
		step("sharing: /kit take", () -> {
			run(b, "kit take testkit");
			check("Bob copied the public kit", KitStore.exists(b.getUUID(), "testkit") && !KitStore.isLocked(b.getUUID(), "testkit"));
			try {
				var kit = KitStore.read(server, b.getUUID(), "testkit");
				check("the copy has the sword and the armor", kit.isPresent() && kit.get().slots()[0].is(Items.DIAMOND_SWORD)
					&& kit.get().slots()[38].is(Items.IRON_CHESTPLATE), kit.isPresent() ? kit.get().slots()[0].toString() : "no kit");
			} catch (java.io.IOException e) {
				check("the copy can be read", false, e.toString());
			}
			run(b, "kit take testkit");
			check("taking it twice is refused", b.heard("already have a kit named"));
			run(b, "publicate testkit");
			check("two public kits can't share a name", b.heard("already a public kit named"));
			run(b, "kit take nothere");
			check("an unknown public kit is refused", b.heard("no public kit named"));
		});
		step("sharing: /unpublish", () -> {
			run(a, "unpublish testkit");
			check("the owner took it off the shelf", PublicKits.find("testkit").isEmpty());
			check("the copy Bob already took stays with him", KitStore.exists(b.getUUID(), "testkit"));
		});
		step("sharing: /private gives a locked copy", () -> {
			run(a, "private testkit Bob");
			check("Bob got the kit under a new name because he already had 'testkit'", KitStore.exists(b.getUUID(), "testkit-2"));
			check("his own 'testkit' was not overwritten", !KitStore.isLocked(b.getUUID(), "testkit"));
			check("the gift is locked", KitStore.isLocked(b.getUUID(), "testkit-2"));
			check("Bob was told", b.heard("gave you the kit 'testkit-2'"));
			try {
				var kit = KitStore.read(server, b.getUUID(), "testkit-2");
				check("the gift keeps the author and the content", kit.isPresent() && kit.get().locked() && kit.get().from().equals("Alice")
					&& kit.get().slots()[0].is(Items.DIAMOND_SWORD));
			} catch (java.io.IOException e) {
				check("the gift can be read", false, e.toString());
			}
			run(a, "private testkit Nobody");
			check("an offline player is refused", a.heard("No player named 'Nobody'"));
			run(a, "private testkit Alice");
			check("you can't give a kit to yourself", a.heard("That is you"));
		});
		step("sharing: a locked kit can be used but not shared or edited", () -> {
			run(b, "publicate testkit-2");
			check("a locked kit can't be published", PublicKits.find("testkit-2").isEmpty() && b.heard("locked: you can't share it"));
			run(b, "private testkit-2 Alice");
			check("a locked kit can't be given away", !KitStore.exists(a.getUUID(), "testkit-2") && b.heard("you can't give it away"));
			run(b, "kit save testkit-2");
			check("a locked kit can't be overwritten", KitStore.isLocked(b.getUUID(), "testkit-2") && b.heard("is locked"));
			run(b, "kit create testkit-2");
			check("a locked kit can't be opened in the workshop", !Workshop.isIn(b.getUUID()) && b.heard("you can use it but not edit it"));
			b.getInventory().clearContent();
			run(b, "kit load testkit-2");
			check("a locked kit can be loaded", b.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
			run(b, "kit list");
			check("/kit list marks the gift", b.heard("testkit-2 (given, locked)"));
			b.getInventory().clearContent();
			b.getInventory().setItem(20, new ItemStack(Items.DIRT, 5));
		});


		// ---------------------------------------------------------------- per-duel rules
		step("rules: menu toggles", () -> {
			run(a, "duel Bob");
			menuStep(a, "testkit");
		});
		step("rules: arena", () -> { menuStep(a, "Circular arena"); });
		step("rules: radius", () -> { menuStep(a, "Radius 100"); });
		step("rules: mode", () -> { menuStep(a, "First to N points"); });
		step("rules: amount", () -> { menuStep(a, "First to 1"); });
		step("rules: switch regeneration off", () -> { menuStep(a, "Natural regeneration: ON"); });
		step("rules: switch fall damage off", () -> {
			check("the regeneration toggle now says OFF", menuHas(a, "Natural regeneration: OFF"));
			menuStep(a, "Fall damage: ON");
		});
		step("rules: switch building off", () -> {
			menuStep(a, "Building: ON");
		});
		step("rules: continue and send", () -> {
			menuStep(a, "Continue");
		});
		step("rules: confirm", () -> {
			menuStep(a, "Send the challenge");
			check("the invitation lists the changed rules", b.heard("Rules: No natural regeneration"), "");
			run(b, "accept Alice");
			Duel d = duel(a);
			check("the duel has the rules", d != null && !d.config.rules().regen() && !d.config.rules().fallDamage() && d.config.rules().hunger() && !d.config.rules().building(),
				d == null ? "no duel" : String.valueOf(d.config.rules()));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("rules: no building, no fall damage, no regeneration", () -> {
			ServerLevel lvl = Terrain.level(server);
			BlockPos ground = a.blockPosition().below();
			ItemStack sword = a.getItemInHand(InteractionHand.MAIN_HAND).copy();
			BlockPos base = ground.east();
			useOn(a, new ItemStack(Items.STONE), new BlockHitResult(new Vec3(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5), Direction.UP, base, false));
			check("placing a block is refused when building is off", lvl.getBlockState(base.above()).isAir(), String.valueOf(lvl.getBlockState(base.above())));
			useOn(a, new ItemStack(Items.CHEST), new BlockHitResult(new Vec3(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5), Direction.UP, base, false));
			check("placing a chest is refused too", lvl.getBlockState(base.above()).isAir());
			a.setItemInHand(InteractionHand.MAIN_HAND, sword);
			boolean dug = a.gameMode.destroyBlock(ground.south());
			check("digging is refused", !dug && lvl.getBlockState(ground.south()).is(Blocks.DIRT));

			a.markLoaded();
			a.invulnerableTime = 0;
			float before = a.getHealth();
			a.hurtServer((ServerLevel) a.level(), a.damageSources().fall(), 6f);
			check("fall damage does nothing when it is off", a.getHealth() >= before - 0.01f, "health=" + a.getHealth());
			a.invulnerableTime = 0;
			a.hurtServer((ServerLevel) a.level(), a.damageSources().generic(), 12f);
			check("other damage still works", a.getHealth() <= before - 5f, "health=" + a.getHealth());

			ruleStartTick = ticks;
			ruleStartHealth = a.getHealth();
		}, () -> ticks - ruleStartTick > 120, 400);
		step("rules: health does not come back by itself", () -> {
			check("no natural regeneration for 6 seconds", a.getHealth() <= ruleStartHealth + 0.01f, ruleStartHealth + " -> " + a.getHealth());
			check("the food bar is held at 17", a.getFoodData().getFoodLevel() == 17, "food=" + a.getFoodData().getFoodLevel());
			hit(a, b);
		}, () -> noDuel(a) && noDuel(b), 800);
		step("rules: protection after the duel", () -> {
			check("Alice got a few seconds of protection after being put back", a.hasEffect(MobEffects.RESISTANCE), "");
			check("Bob too", b.hasEffect(MobEffects.RESISTANCE));
		});

		step("rules: hunger off in the flat arena", () -> {
			invite(a, b, new DuelConfig("testkit", ArenaKind.FLAT, false, 0, WinMode.FIRST_TO, 1, new DuelConfig.Rules(true, true, false, true)));
			check("the duel started", duel(a) != null && !duel(a).config.rules().hunger());
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("rules: the food bar stays full", () -> {
			a.getFoodData().setFoodLevel(3);
		}, () -> a.getFoodData().getFoodLevel() == 20, 100);
		step("rules: hunger duel ends", () -> {
			check("the food bar was refilled", a.getFoodData().getFoodLevel() == 20);
			hit(a, b);
		}, () -> noDuel(a) && noDuel(b), 800);

		// ---------------------------------------------------------------- spectating
		step("spectate: setup", () -> {
			carl = spawn("Carl");
			invite(a, b, new DuelConfig("testkit", false, WinMode.FIRST_TO, 1));
			carlTick = ticks;
		}, () -> phaseIs(a, Duel.Phase.FIGHTING) && ticks - carlTick > 30, 800);
		step("spectate: errors", () -> {
			run(carl, "spectate Nobody");
			check("an unknown player is refused", carl.heard("No player named 'Nobody'"));
			run(carl, "spectate Carl");
			check("you can't watch yourself", carl.heard("can't watch yourself"));
			run(carl, "duels");
			check("/duels lists the running duel with a [WATCH] button", carl.heard("Duels right now (1)") && carl.heard("Alice 0 - 0 Bob") && carl.heard("[WATCH]"));
		});
		step("spectate: watch the duel", () -> {
			run(carl, "spectate Alice");
			check("Carl is watching", Spectators.isIn(carl.getUUID()));
			check("Carl is in spectator mode", carl.gameMode() == GameType.SPECTATOR);
			check("Carl is in the duel world", carl.level().dimension().equals(Arena.DIMENSION), where(carl));
			check("Carl's inventory was backed up", PlayerBackup.exists(carl.getUUID()));
			check("Carl got a [LEAVE] button", carl.heard("[LEAVE]"));
			run(carl, "duel Alice");
			check("a spectator can't start a duel", carl.heard("busy"));
			carlTick = ticks;
		}, () -> ticks - carlTick > 12, 100);
		step("spectate: /leave", () -> {
			check("Carl's sidebar shows the score", sidebarHas(carl, "Spectating") && sidebarHas(carl, "Alice: 0"), String.valueOf(sidebar(carl)));
			run(carl, "leave");
			check("Carl is back in the overworld in survival", !Spectators.isIn(carl.getUUID()) && carl.level().dimension().equals(server.overworld().dimension())
				&& carl.gameMode() == GameType.SURVIVAL, where(carl) + " " + carl.gameMode());
			check("the backup is deleted", !PlayerBackup.exists(carl.getUUID()));
			run(carl, "spectate Bob");
			check("he can watch again (through the other player)", Spectators.isIn(carl.getUUID()));
		});
		step("spectate: the duel ends and the spectator goes back by himself", () -> {
			hit(a, b);
		}, () -> noDuel(a) && noDuel(b) && !Spectators.isIn(carl.getUUID()), 800);
		step("spectate: after the duel", () -> {
			check("Carl is back in the overworld in survival", carl.level().dimension().equals(server.overworld().dimension())
				&& carl.gameMode() == GameType.SURVIVAL, where(carl) + " " + carl.gameMode());
			check("no backup is left", !PlayerBackup.exists(carl.getUUID()));
			check("both duelists got the summary of the duel", a.heard("Summary (") && b.heard("Summary (") && a.heard("Alice: ") && a.heard("Bob: ") && a.heard("best combo"));
			check("the summary counted the hit that decided it", a.heard("Alice: 1 hit"), "Alice heard the summary: " + a.heard("Summary ("));
			check("the rematch button is there", a.heard("[REMATCH]"));
			run(carl, "duels");
			check("/duels says when nobody is dueling", carl.heard("Nobody is dueling right now"));
		});

		// ---------------------------------------------------------------- lobby
		step("lobby: only admins can set it", () -> {
			run(a, "setlobby");
			check("a normal player can't set the lobby", !Lobby.isSet());
			run(a, "lobby");
			check("/lobby without a lobby says so", a.heard("no lobby yet"));
			carl.teleportTo((ServerLevel) carl.level(), 50.5, carl.getY(), 50.5, java.util.Set.of(), 0f, 0f, true);
			try {
				Lobby.set(carl);
			} catch (java.io.IOException e) {
				check("the lobby can be saved", false, e.toString());
			}
			check("the lobby is set", Lobby.isSet());
			a.teleportTo((ServerLevel) a.level(), -30.5, a.getY(), -30.5, java.util.Set.of(), 0f, 0f, true);
			b.teleportTo((ServerLevel) b.level(), -20.5, b.getY(), -30.5, java.util.Set.of(), 0f, 0f, true);
			invite(a, b, new DuelConfig("testkit", false, WinMode.FIRST_TO, 1));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 800);
		step("lobby: finishing a duel sends everybody to the lobby", () -> {
			hit(a, b);
		}, () -> noDuel(a) && noDuel(b), 800);
		step("lobby: both are at the lobby", () -> {
			check("Alice is at the lobby", Math.abs(a.getX() - 50.5) < 2 && Math.abs(a.getZ() - 50.5) < 2, where(a));
			check("Bob is at the lobby", Math.abs(b.getX() - 50.5) < 2 && Math.abs(b.getZ() - 50.5) < 2, where(b));
			check("they were told", a.heard("Welcome to the lobby"));
			run(a, "lobby return off");
			check("Alice turned the lobby off for herself", !Prefs.lobbyReturn(a.getUUID()));
			a.teleportTo((ServerLevel) a.level(), -30.5, a.getY(), -30.5, java.util.Set.of(), 0f, 0f, true);
			invite(a, b, new DuelConfig("testkit", false, WinMode.FIRST_TO, 1));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 800);
		step("lobby: with 'return off' you go back to where you were", () -> {
			hit(a, b);
		}, () -> noDuel(a) && noDuel(b), 800);
		step("lobby: Alice went back, Bob to the lobby", () -> {
			check("Alice is where she was before the duel", Math.abs(a.getX() + 30.5) < 2 && Math.abs(a.getZ() + 30.5) < 2, where(a));
			check("Bob is at the lobby again", Math.abs(b.getX() - 50.5) < 2 && Math.abs(b.getZ() - 50.5) < 2, where(b));
			run(a, "lobby");
			check("/lobby takes you there", Math.abs(a.getX() - 50.5) < 2 && Math.abs(a.getZ() - 50.5) < 2, where(a));
			run(a, "lobby return on");
			check("and Alice turned it on again", Prefs.lobbyReturn(a.getUUID()));
			check("the lobby file is inside the world folder", java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.resolve("pvpduels").resolve("lobby.json")));
			check("the lobby is not protected by default", Lobby.protectRadius() == 0);
		});

		// ---------------------------------------------------------------- lobby items, NPCs and the guide book
		step("lobby items: everybody in the lobby gets them", () -> {
			// the lobby is where Alice, Bob and Carl are
			check("Carl is in the lobby too", Lobby.contains(carl), where(carl));
		}, () -> {
			boolean ok = LobbyKit.isActive(a.getUUID()) && LobbyKit.isActive(b.getUUID()) && LobbyKit.isActive(carl.getUUID());
			if (!ok && ticks % 40 == 0) log("waiting for the lobby items: Alice " + LobbyKit.why(a) + " | Bob " + LobbyKit.why(b) + " | Carl " + LobbyKit.why(carl));
			return ok;
		}, 200);
		step("lobby items: what is in each slot", () -> {
			var inv = a.getInventory();
			check("slot 1 is Send Duel (a sword)", LobbyKit.kindOf(inv.getItem(0)) != null && "send".equals(LobbyKit.kindOf(inv.getItem(0)))
				&& inv.getItem(0).is(Items.DIAMOND_SWORD) && inv.getItem(0).getHoverName().getString().equals("Send Duel"), inv.getItem(0).toString());
			check("Send Duel is green", inv.getItem(0).getHoverName().getStyle().getColor() != null
				&& inv.getItem(0).getHoverName().getStyle().getColor().getValue() == 0x55FF55);
			check("slot 2 is the Leave barrier", "leave".equals(LobbyKit.kindOf(inv.getItem(1))) && inv.getItem(1).is(Items.BARRIER)
				&& inv.getItem(1).getHoverName().getString().equals("Leave"), inv.getItem(1).toString());
			check("slot 8 is the Create+ grass block", "create".equals(LobbyKit.kindOf(inv.getItem(7))) && inv.getItem(7).is(Items.GRASS_BLOCK)
				&& inv.getItem(7).getHoverName().getString().equals("Create+"), inv.getItem(7).toString());
			check("slot 9 is the guide book", "guide".equals(LobbyKit.kindOf(inv.getItem(8))) && GuideBook.isGuide(inv.getItem(8)), inv.getItem(8).toString());
			check("the other hotbar slots are not touched", LobbyKit.kindOf(inv.getItem(2)) == null && LobbyKit.kindOf(inv.getItem(3)) == null
				&& LobbyKit.kindOf(inv.getItem(4)) == null && LobbyKit.kindOf(inv.getItem(5)) == null && LobbyKit.kindOf(inv.getItem(6)) == null);
			check("the stash is on disk", LobbyKit.hasStashFile(a.getUUID()));
			check("the game on Alice's side sees them too", clientMatches(a), clientDiff(a));

			var book = inv.getItem(8).get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
			check("the book has the content", book != null);
			if (book != null) {
				var pages = book.getPages(false);
				int blank = 0;
				int tooLong = 0;
				for (var page : pages) {
					if (page.getString().isEmpty()) blank++;
					else if (page.getString().length() > 260) tooLong++;
				}
				check("the book has " + GuideBook.pageCount() + " pages", pages.size() == GuideBook.pageCount(), String.valueOf(pages.size()));
				check("every topic ends with a blank page", blank == GuideBook.topicCount(), blank + " blank pages for " + GuideBook.topicCount() + " topics");
				check("no page has too much text", tooLong == 0, tooLong + " long pages");
				int links = 0;
				for (Component part : pages.get(0).getSiblings()) {
					if (part.getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.ChangePage) links++;
				}
				check("page 1 is the index with a link to every topic", pages.get(0).getString().contains("Lobby") && pages.get(0).getString().contains("About")
					&& links == GuideBook.topicCount(), links + " links");
				StringBuilder all = new StringBuilder();
				for (var page : pages) all.append(page.getString()).append('\n');
				for (String word : new String[] {"/duels", "/npc", "/queue", "/top kit", "Send Duel", "Create+", "/botduel", "/kit save", "/layout", "/top", "/spectate", "About this server"}) {
					check("the book mentions " + word, all.toString().contains(word));
				}
			}
		});

		step("lobby items: they can't be moved, swapped or thrown", () -> {
			var inv = a.getInventory();
			inv.setItem(3, new ItemStack(Items.COBBLESTONE, 7));
			inv.setItem(20, new ItemStack(Items.STONE, 5));
			var menu = a.containerMenu;
			// menu slots 36-44 are the hotbar
			menu.clicked(36, 0, ContainerInput.PICKUP, a);
			check("picking up the sword does nothing", menu.getCarried().isEmpty() && "send".equals(LobbyKit.kindOf(inv.getItem(0))), String.valueOf(menu.getCarried()));
			menu.clicked(37, 0, ContainerInput.QUICK_MOVE, a);
			check("shift-clicking the barrier does nothing", "leave".equals(LobbyKit.kindOf(inv.getItem(1))));
			menu.clicked(44, 0, ContainerInput.THROW, a);
			menu.clicked(44, 1, ContainerInput.THROW, a);
			check("throwing the book out does nothing", "guide".equals(LobbyKit.kindOf(inv.getItem(8))));
			menu.clicked(20, 7, ContainerInput.SWAP, a); // hotbar key 8 on a backpack slot: would swap with the grass block
			check("swapping with the number key does nothing", "create".equals(LobbyKit.kindOf(inv.getItem(7))) && inv.getItem(20).is(Items.STONE), inv.getItem(7) + " / " + inv.getItem(20));
			menu.clicked(20, 1, ContainerInput.SWAP, a); // the number key 2 on a backpack slot: would swap with the barrier
			check("the barrier stays when a number key is pressed", "leave".equals(LobbyKit.kindOf(inv.getItem(1))) && inv.getItem(20).is(Items.STONE));
			menu.clicked(36, 0, ContainerInput.PICKUP_ALL, a);
			check("double-click collecting does nothing", menu.getCarried().isEmpty());
			inv.setSelectedSlot(0);
			a.drop(true);
			check("Q on the sword drops nothing", "send".equals(LobbyKit.kindOf(inv.getItem(0))) && inv.getItem(0).getCount() == 1);
			inv.setSelectedSlot(8);
			a.drop(false);
			check("Q on the book drops nothing", "guide".equals(LobbyKit.kindOf(inv.getItem(8))));
			check("the sword was not thrown into the world", ((ServerLevel) a.level()).getEntities((net.minecraft.world.entity.Entity) null,
				a.getBoundingBox().inflate(6), e -> e instanceof net.minecraft.world.entity.item.ItemEntity item && LobbyKit.isLobbyItem(item.getItem())).isEmpty());
			// the other hotbar slots still work as always
			menu.clicked(39, 0, ContainerInput.PICKUP, a);
			check("an ordinary slot can still be picked up", menu.getCarried().is(Items.COBBLESTONE) && menu.getCarried().getCount() == 7, String.valueOf(menu.getCarried()));
			menu.clicked(39, 0, ContainerInput.PICKUP, a);
			check("and put down again", menu.getCarried().isEmpty() && inv.getItem(3).is(Items.COBBLESTONE));
			inv.setSelectedSlot(3);

			// the game would put things back by itself anyway: break one on purpose
			inv.setItem(1, new ItemStack(Items.DIRT));
			inv.setItem(5, LobbyKit.make("leave")); // a copy in the wrong place
			a.getInventory().setItem(20, new ItemStack(Items.STONE, 5));
			carlTick = ticks;
		}, () -> ticks - carlTick > 12, 100);
		step("lobby items: broken ones are repaired", () -> {
			var inv = a.getInventory();
			check("the barrier is back in slot 2", "leave".equals(LobbyKit.kindOf(inv.getItem(1))));
			check("the copy in the wrong slot is gone", LobbyKit.kindOf(inv.getItem(5)) == null);
			boolean dirtKept = false;
			for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.DIRT)) dirtKept = true;
			StringBuilder hotbar = new StringBuilder();
			for (int i = 0; i < 12; i++) hotbar.append(i).append('=').append(inv.getItem(i).getItem()).append(' ');
			check("the dirt that was in the barrier's slot was not lost", dirtKept, hotbar.toString());
			check("the game on Alice's side agrees", clientMatches(a), clientDiff(a));
			inv.clearContent(); // the lobby items come back by themselves
			carlTick = ticks;
		}, () -> ticks - carlTick > 12, 100);

		step("lobby items: your own things come back when you leave the lobby", () -> {
			LobbyKit.strip(a);
			var inv = a.getInventory();
			check("no lobby item is left after strip", LobbyKit.kindOf(inv.getItem(0)) == null && LobbyKit.kindOf(inv.getItem(1)) == null
				&& LobbyKit.kindOf(inv.getItem(7)) == null && LobbyKit.kindOf(inv.getItem(8)) == null);
			check("the stash file is gone", !LobbyKit.hasStashFile(a.getUUID()));
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.IRON_SWORD));
			inv.setItem(1, new ItemStack(Items.GOLDEN_APPLE, 5));
			inv.setItem(7, new ItemStack(Items.ENDER_PEARL, 3));
			inv.setItem(8, new ItemStack(Items.COOKED_BEEF, 20));
			inv.setItem(4, new ItemStack(Items.SHIELD));
			carlTick = ticks;
		}, () -> true, 10);
		step("lobby items: they hide Alice's items", () -> {
			var inv = a.getInventory();
			check("Alice has them again", LobbyKit.isActive(a.getUUID()) && "send".equals(LobbyKit.kindOf(inv.getItem(0))));
			check("the item next to them is untouched", inv.getItem(4).is(Items.SHIELD));
			run(a, "kit save lobbykit");
			check("saving a kit in the lobby works", KitStore.exists(a.getUUID(), "lobbykit"));
			try {
				var kit = KitStore.read(server, a.getUUID(), "lobbykit").orElseThrow();
				check("the kit has Alice's own items, not the lobby ones", kit.slots()[0].is(Items.IRON_SWORD) && kit.slots()[1].is(Items.GOLDEN_APPLE)
					&& kit.slots()[7].is(Items.ENDER_PEARL) && kit.slots()[8].is(Items.COOKED_BEEF) && kit.slots()[4].is(Items.SHIELD),
					kit.slots()[0] + " " + kit.slots()[1] + " " + kit.slots()[7] + " " + kit.slots()[8]);
				boolean lobbyInKit = false;
				for (ItemStack stack : kit.slots()) if (LobbyKit.isLobbyItem(stack)) lobbyInKit = true;
				check("no lobby item went into the kit", !lobbyInKit);
				check("the kit has exactly the 5 stacks", kit.stacks() == 5, String.valueOf(kit.stacks()));
			} catch (java.io.IOException | RuntimeException e) {
				check("the kit can be read", false, e.toString());
			}
			carlTick = ticks;
		}, () -> true, 10);
		step("lobby items: walking out of the lobby gives them back", () -> {
			a.teleportTo((ServerLevel) a.level(), 400.5, a.getY(), 400.5, java.util.Set.of(), 0f, 0f, true);
			carlTick = ticks;
		}, () -> ticks - carlTick > 6, 100);
		step("lobby items: Alice has her own things", () -> {
			var inv = a.getInventory();
			check("the lobby items are gone", !LobbyKit.isActive(a.getUUID()) && LobbyKit.kindOf(inv.getItem(0)) == null && LobbyKit.kindOf(inv.getItem(8)) == null);
			check("the iron sword, apples, pearls and beef are back in their slots", inv.getItem(0).is(Items.IRON_SWORD) && inv.getItem(1).is(Items.GOLDEN_APPLE)
				&& inv.getItem(1).getCount() == 5 && inv.getItem(7).is(Items.ENDER_PEARL) && inv.getItem(8).is(Items.COOKED_BEEF) && inv.getItem(8).getCount() == 20,
				inv.getItem(0) + " " + inv.getItem(1) + " " + inv.getItem(7) + " " + inv.getItem(8));
			check("the stash file is gone", !LobbyKit.hasStashFile(a.getUUID()));
			check("the game on Alice's side agrees", clientMatches(a), clientDiff(a));
			a.teleportTo((ServerLevel) a.level(), 50.5, a.getY(), 50.5, java.util.Set.of(), 0f, 0f, true);
			carlTick = ticks;
		}, () -> true, 10);
		step("lobby items: back in the lobby, then a crash (the memory is lost)", () -> {
			check("Alice has them again", LobbyKit.isActive(a.getUUID()) && "send".equals(LobbyKit.kindOf(a.getInventory().getItem(0))));
			LobbyKit.forgetInMemory(a.getUUID());
			check("only the file on disk remembers her things", !LobbyKit.isActive(a.getUUID()) && LobbyKit.hasStashFile(a.getUUID()));
			// next join: the file is read and given back
			LobbyKit.strip(a);
			var inv = a.getInventory();
			check("the things come back from the file", inv.getItem(0).is(Items.IRON_SWORD) && inv.getItem(1).is(Items.GOLDEN_APPLE) && inv.getItem(7).is(Items.ENDER_PEARL)
				&& inv.getItem(8).is(Items.COOKED_BEEF), inv.getItem(0) + " " + inv.getItem(1) + " " + inv.getItem(7) + " " + inv.getItem(8));
			check("and the file is deleted", !LobbyKit.hasStashFile(a.getUUID()));
			inv.clearContent();
			carlTick = ticks;
		}, () -> true, 10);

		step("lobby items: Send Duel opens the player list", () -> {
			check("Alice has the items again", "send".equals(LobbyKit.kindOf(a.getInventory().getItem(0))));
			a.getInventory().setSelectedSlot(0);
			LobbyKit.clearCooldowns();
			a.gameMode.useItem(a, (ServerLevel) a.level(), a.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
			check("right-clicking the sword opens a menu", a.containerMenu instanceof ChoiceMenu);
			check("it lists Bob", menuHas(a, "Bob"));
			check("it doesn't list Alice", !menuHas(a, "Alice"));
			menuStep(a, "Bob");
			check("choosing Bob starts the duel menus (kit first)", a.containerMenu instanceof ChoiceMenu && menuHas(a, "testkit"));
			a.closeContainer();
			// hitting a player with the sword does the same
			LobbyKit.clearCooldowns();
			var result = net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker().interact(a, a.level(), InteractionHand.MAIN_HAND, b, null);
			check("hitting Bob with the sword opens the duel menus instead of hurting him", a.containerMenu instanceof ChoiceMenu && menuHas(a, "testkit")
				&& result == net.minecraft.world.InteractionResult.SUCCESS, String.valueOf(result));
			a.closeContainer();
		});
		step("lobby items: Leave cancels requests and goes to the lobby spawn", () -> {
			DuelManager.sendInvite(a, b, a.getUUID(), new DuelConfig("testkit", false, WinMode.FIRST_TO, 1), false);
			check("Bob has a request from Alice", DuelManager.pendingFrom(b.getUUID()).contains("Alice"));
			a.teleportTo((ServerLevel) a.level(), 70.5, a.getY(), 60.5, java.util.Set.of(), 0f, 0f, true);
			a.getInventory().setSelectedSlot(1);
			LobbyKit.clearCooldowns();
			a.gameMode.useItem(a, (ServerLevel) a.level(), a.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
			check("the request is cancelled", !DuelManager.pendingFrom(b.getUUID()).contains("Alice"));
			check("Alice is back at the lobby spawn", Math.abs(a.getX() - 50.5) < 2 && Math.abs(a.getZ() - 50.5) < 2, where(a));
			check("and was told", a.heard("Cancelled 1 duel request"));
			a.getInventory().setSelectedSlot(3);
		});
		step("lobby items: Create+ opens the kit and arena menu", () -> {
			a.getInventory().setSelectedSlot(7);
			LobbyKit.clearCooldowns();
			a.gameMode.useItem(a, (ServerLevel) a.level(), a.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
			check("right-clicking the grass block opens the Create+ menu", a.containerMenu instanceof ChoiceMenu && menuHas(a, "Create Kit") && menuHas(a, "Create Arena"));
			menuStep(a, "Create Arena");
			check("the arena builder says what is coming", a.heard("Builder rank"));
			LobbyKit.clearCooldowns();
			a.gameMode.useItem(a, (ServerLevel) a.level(), a.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
			menuStep(a, "Create Kit");
			check("Create Kit offers a new kit", menuHas(a, "New kit"));
			check("and Alice's kits to edit", menuHas(a, "Edit one of my kits"));
			menuStep(a, "New kit");
			check("Alice is in the kit workshop", Workshop.isIn(a.getUUID()));
			check("and got a [LEAVE] button", a.heard("[LEAVE]"));
			boolean any = false;
			for (int i = 0; i < a.getInventory().getContainerSize(); i++) if (LobbyKit.isLobbyItem(a.getInventory().getItem(i))) any = true;
			check("the lobby items are not in the workshop", !any && !LobbyKit.isActive(a.getUUID()));
			carlTick = ticks;
		}, () -> true, 10);
		step("lobby items: leaving the workshop brings Alice back to the lobby with them", () -> {
			run(a, "leave");
			check("Alice left the workshop", !Workshop.isIn(a.getUUID()));
			carlTick = ticks;
		}, () -> LobbyKit.isActive(a.getUUID()), 100);
		step("lobby items: all fine after the workshop", () -> {
			check("Alice is in the lobby with the items", "send".equals(LobbyKit.kindOf(a.getInventory().getItem(0))) && "guide".equals(LobbyKit.kindOf(a.getInventory().getItem(8))));
			check("the game on Alice's side agrees", clientMatches(a), clientDiff(a));
			a.getInventory().setSelectedSlot(3);
		});

		step("npc: only admins can place one", () -> {
			run(carl, "npc add bot");
			check("a normal player can't place an NPC", Npcs.around((ServerLevel) carl.level(), carl.getX(), carl.getY(), carl.getZ(), 4).isEmpty());
		});
		step("npc: place the three NPCs", () -> {
			ServerLevel level = (ServerLevel) carl.level();
			int n = 0;
			for (Npcs.Kind kind : Npcs.Kind.values()) {
				net.minecraft.world.entity.Entity npc = Npcs.placeAt(level, server.createCommandSourceStack(), kind, 56.5 + 3 * n, carl.getY(), 56.5, 180f);
				check("the " + kind.title + " NPC exists", npc != null);
				if (npc != null) {
					check("it is a mannequin, invulnerable, with the tag", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(npc.getType()).getPath().equals("mannequin")
						&& npc.isInvulnerable() && npc.entityTags().contains(kind.tag()), npc.getType() + " " + npc.entityTags());
					check("its name is shown", npc.hasCustomName() && npc.isCustomNameVisible() && npc.getCustomName().getString().equals(kind.title),
						String.valueOf(npc.getCustomName()));
					if (npc instanceof net.minecraft.world.entity.decoration.Mannequin mannequin) {
						log("skin of the " + kind.id + " NPC: " + mannequin.getProfile().skinPatch());
					}
				}
				n++;
			}
			check("three NPCs in total", Npcs.around(level, 59.5, carl.getY(), 56.5, 8).size() == 3, String.valueOf(Npcs.around(level, 59.5, carl.getY(), 56.5, 8).size()));
			run(carl, "npc list");
		});
		step("npc: clicking them opens the menus", () -> {
			ServerLevel level = (ServerLevel) a.level();
			var npcs = Npcs.around(level, 59.5, carl.getY(), 56.5, 8);
			for (net.minecraft.world.entity.Entity npc : npcs) {
				var kind = Npcs.kindOf(npc);
				a.closeContainer();
				var result = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker().interact(a, level, InteractionHand.MAIN_HAND, npc, null);
				switch (kind) {
					case BOT -> check("the Practice Bot opens the bot menu", a.containerMenu instanceof ChoiceMenu && menuHas(a, "testkit"));
					case KIT -> check("the Kit Workshop opens the kit menu", a.containerMenu instanceof ChoiceMenu && menuHas(a, "New kit"));
					case ARENA -> check("the Arena Builder says what is coming", a.heard("Builder rank"));
				}
				check("the click is used up (" + kind.id + ")", result == net.minecraft.world.InteractionResult.SUCCESS, String.valueOf(result));
				// hitting works like clicking
				a.closeContainer();
				var hitResult = net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker().interact(a, level, InteractionHand.MAIN_HAND, npc, null);
				check("hitting does not hurt it (" + kind.id + ")", hitResult == net.minecraft.world.InteractionResult.FAIL, String.valueOf(hitResult));
			}
			a.closeContainer();
			// with a lobby item in the hand the NPC still gets the click
			a.getInventory().setSelectedSlot(0);
			a.closeContainer();
			var bot = Npcs.nearest(level, 56.5, carl.getY(), 56.5, 1.5);
			net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker().interact(a, level, InteractionHand.MAIN_HAND, bot, null);
			check("holding Send Duel, the Practice Bot still opens its menu", a.containerMenu instanceof ChoiceMenu && menuHas(a, "testkit"));
			a.closeContainer();
			a.getInventory().setSelectedSlot(3);
			run(a, "npc remove");
			check("a normal player can't remove one", Npcs.around(level, 59.5, carl.getY(), 56.5, 8).size() == 3);
		});
		step("npc: they are saved with the world and can be removed", () -> {
			ServerLevel level = (ServerLevel) a.level();
			carl.teleportTo(level, 59.5, carl.getY(), 56.5, java.util.Set.of(), 0f, 0f, true);
			net.minecraft.world.entity.Entity npc = Npcs.nearest(level, 59.5, carl.getY(), 56.5, 8);
			check("an NPC is closest to Carl", npc != null);
			// every NPC is removed so the next tests are not disturbed
			for (net.minecraft.world.entity.Entity left : Npcs.around(level, 59.5, carl.getY(), 56.5, 10)) left.discard();
			check("no NPC is left", Npcs.around(level, 59.5, carl.getY(), 56.5, 10).isEmpty());
		});

		// ---------------------------------------------------------------- lobby protection
		step("lobby protection: a platform in the air", () -> {
			ServerLevel level = (ServerLevel) a.level();
			botX = a.getY();
			try {
				Lobby.setAt(level.dimension().identifier().toString(), 50.5, 100.0, 50.5, 0f, 0f);
			} catch (java.io.IOException e) {
				check("the lobby can be moved", false, e.toString());
			}
			run(a, "lobby protect 20");
			check("a normal player cannot protect the lobby", Lobby.protectRadius() == 0);
			try {
				Lobby.setProtectRadius(20);
			} catch (java.io.IOException e) {
				check("the protection can be turned on", false, e.toString());
			}
			check("the protection is on", Lobby.protectRadius() == 20);
			for (int x = 46; x <= 56; x++) for (int z = 46; z <= 56; z++) level.setBlock(at(x, 99, z), Blocks.STONE.defaultBlockState(), 3);
			// a platform far away, outside of the protected area, to compare
			for (int x = -34; x <= -26; x++) for (int z = -34; z <= -26; z++) level.setBlock(at(x, 99, z), Blocks.STONE.defaultBlockState(), 3);
			// the few seconds of protection after a duel would hide what this test wants to see
			a.removeAllEffects();
			b.removeAllEffects();
			// Bob was left in creative mode by an earlier step, and nobody can hurt a creative player
			a.setGameMode(GameType.SURVIVAL);
			b.setGameMode(GameType.SURVIVAL);
			a.teleportTo(level, 50.5, 100, 50.5, java.util.Set.of(), 0f, 0f, true);
			b.teleportTo(level, 50.5, 100, 52.5, java.util.Set.of(), 0f, 0f, true);
			botTick = ticks;
		}, () -> ticks - botTick > 5, 100);
		step("lobby protection: nothing can be broken, placed or hit", () -> {
			ServerLevel level = (ServerLevel) a.level();
			check("protected radius and the lobby spot are saved", java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.resolve("pvpduels").resolve("lobby.json")));
			BlockPos block = at(54, 99, 50);
			a.gameMode.destroyBlock(block);
			check("breaking a block in the lobby does nothing", level.getBlockState(block).is(Blocks.STONE), String.valueOf(level.getBlockState(block)));
			useOn(a, new ItemStack(Items.DIRT), new BlockHitResult(new Vec3(54.5, 100.0, 50.5), Direction.UP, block, false));
			check("placing a block in the lobby does nothing", level.getBlockState(at(54, 100, 50)).isAir(), String.valueOf(level.getBlockState(at(54, 100, 50))));
			float before = b.getHealth();
			softHit(a, b, 4f);
			check("players cannot hurt each other in the lobby", b.getHealth() == before, before + " -> " + b.getHealth());
			check("the protection tells you why", a.heard("The lobby is protected"));
			// the same things work far from the lobby
			a.teleportTo(level, -30.5, 100, -30.5, java.util.Set.of(), 0f, 0f, true);
			b.teleportTo(level, -30.5, 100, -28.5, java.util.Set.of(), 0f, 0f, true);
			botTick = ticks;
		}, () -> ticks - botTick > 5, 100);
		step("lobby protection: outside the protected area everything works", () -> {
			ServerLevel level = (ServerLevel) a.level();
			BlockPos block = at(-30, 99, -27);
			a.gameMode.destroyBlock(block);
			check("a block can be broken outside of it", level.getBlockState(block).isAir(), String.valueOf(level.getBlockState(block)));
			useOn(a, new ItemStack(Items.DIRT), new BlockHitResult(new Vec3(-29.5, 99.5, -27.0), Direction.SOUTH, at(-30, 99, -28), false));
			check("and a block can be placed", level.getBlockState(at(-30, 99, -27)).is(Blocks.DIRT), String.valueOf(level.getBlockState(at(-30, 99, -27))));
			float before = b.getHealth();
			softHit(a, b, 4f);
			check("and players can hurt each other", b.getHealth() < before, before + " -> " + b.getHealth());
			a.teleportTo(level, 50.5, 15, 50.5, java.util.Set.of(), 0f, 0f, true);
			botTick = ticks;
		}, () -> ticks - botTick > 25, 100);
		step("lobby protection: somebody who falls off is brought back", () -> {
			check("Alice was brought back to the lobby", a.getY() > 90 && Math.abs(a.getX() - 50.5) < 2, where(a));
			check("and was told", a.heard("You fell off the island"));
			try {
				Lobby.setProtectRadius(0);
			} catch (java.io.IOException e) {
				check("the protection can be turned off", false, e.toString());
			}
			check("the protection is off", Lobby.protectRadius() == 0);
			try {
				Lobby.clear();
			} catch (java.io.IOException e) {
				check("the lobby can be removed", false, e.toString());
			}
			check("the lobby is gone", !Lobby.isSet() && !java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.resolve("pvpduels").resolve("lobby.json")));
			b.setHealth(b.getMaxHealth());
			a.teleportTo((ServerLevel) a.level(), -30.5, botX, -30.5, java.util.Set.of(), 0f, 0f, true);
			b.teleportTo((ServerLevel) b.level(), -20.5, botX, -30.5, java.util.Set.of(), 0f, 0f, true);
		});

		// ---------------------------------------------------------------- automatic layout
		step("auto layout: three kits", () -> {
			dora = spawn("Dora");
			carlTick = ticks;
		}, () -> ticks - carlTick > 20, 200);
		step("auto layout: /layout auto", () -> {
			run(carl, "layout auto");
			check("without kits there is nothing to look at", carl.heard("no kits"));

			net.minecraft.world.entity.player.Inventory inv = dora.getInventory();
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			inv.setItem(1, new ItemStack(Items.GOLDEN_APPLE, 8));
			inv.setItem(2, new ItemStack(Items.ENDER_PEARL, 16));
			inv.setItem(8, new ItemStack(Items.COOKED_BEEF, 32));
			inv.setItem(20, new ItemStack(Items.STONE, 4));
			run(dora, "kit save dk1");
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.NETHERITE_SWORD));
			inv.setItem(1, new ItemStack(Items.GOLDEN_APPLE, 8));
			inv.setItem(2, new ItemStack(Items.ENDER_PEARL, 16));
			inv.setItem(8, new ItemStack(Items.COOKED_BEEF, 32));
			inv.setItem(30, new ItemStack(Items.DIRT, 4));
			run(dora, "kit save dk2");
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			inv.setItem(1, new ItemStack(Items.GOLDEN_APPLE, 8));
			inv.setItem(3, new ItemStack(Items.ENDER_PEARL, 16));
			inv.setItem(8, new ItemStack(Items.COOKED_BEEF, 32));
			run(dora, "kit save dk3");
			check("Dora has three kits", KitStore.list(dora.getUUID()).size() == 3);

			run(dora, "layout auto");
			var profile = com.pvpduels.layout.LayoutStore.get(dora.getUUID()).profiles.get("auto");
			check("the layout 'auto' exists", profile != null, dora.heard("Looked at") ? "" : "no answer");
			if (profile == null) return;
			check("slot 1 wants the diamond sword first, then any sword", profile.slots.get(1) != null
				&& profile.slots.get(1).get(0).equals("minecraft:diamond_sword") && profile.slots.get(1).contains("sword"), String.valueOf(profile.slots.get(1)));
			check("slot 2 wants the golden apple", profile.slots.get(2) != null && profile.slots.get(2).get(0).equals("minecraft:golden_apple")
				&& profile.slots.get(2).contains("gapple"), String.valueOf(profile.slots.get(2)));
			check("slot 3 wants the ender pearl (2 of 3 kits)", profile.slots.get(3) != null && profile.slots.get(3).get(0).equals("minecraft:ender_pearl"),
				String.valueOf(profile.slots.get(3)));
			check("slot 4 gets nothing (the pearl was there only once)", profile.slots.get(4) == null, String.valueOf(profile.slots.get(4)));
			check("slot 9 wants the cooked beef", profile.slots.get(9) != null && profile.slots.get(9).get(0).equals("minecraft:cooked_beef"),
				String.valueOf(profile.slots.get(9)));
			check("items that are in only one kit get no rule", profile.slots.get(21) == null && profile.slots.get(31) == null);

			// a scrambled inventory is sorted by the new layout
			inv.clearContent();
			inv.setItem(5, new ItemStack(Items.DIAMOND_SWORD));
			inv.setItem(7, new ItemStack(Items.GOLDEN_APPLE, 8));
			inv.setItem(10, new ItemStack(Items.ENDER_PEARL, 16));
			inv.setItem(0, new ItemStack(Items.COOKED_BEEF, 32));
			com.pvpduels.layout.InventorySorter.sort(dora, profile);
			check("the sorter puts everything where your kits keep it", inv.getItem(0).is(Items.DIAMOND_SWORD) && inv.getItem(1).is(Items.GOLDEN_APPLE)
				&& inv.getItem(2).is(Items.ENDER_PEARL) && inv.getItem(8).is(Items.COOKED_BEEF));
			run(dora, "layout auto mine");
			check("another name makes another layout", com.pvpduels.layout.LayoutStore.get(dora.getUUID()).profiles.containsKey("mine"));
			run(dora, "layout auto mine");
			check("an existing layout with rules is not overwritten", dora.heard("already has rules"));
		});

		// ---------------------------------------------------------------- practice duel against a bot
		step("bot duel: /botduel asks which kit", () -> {
			botStatsBefore = StatsStore.get(a.getUUID()).duels;
			botEloBefore = StatsStore.get(a.getUUID()).elo;
			a.getInventory().clearContent();
			a.getInventory().setItem(21, new ItemStack(Items.STONE, 3));
			run(a, "botduel");
			check("the kit list opens", menuHas(a, "testkit"));
			menuStep(a, "testkit");
		});
		step("bot duel: the question 'Static bot?'", () -> {
			check("the question has a Yes and a No", menuHas(a, "Yes") && menuHas(a, "No"));
			menuStep(a, "No");
			check("No leads to the bot types", menuHas(a, "Passive") && menuHas(a, "Easy") && menuHas(a, "Normal") && menuHas(a, "Hard"));
			menuStep(a, "Back");
			check("Back returns to the question", menuHas(a, "Yes") && menuHas(a, "No"));
			a.closeContainer();
			check("closing the menu starts nothing", noDuel(a) && server.getPlayerList().getPlayerByName("Bot") == null);
		});
		step("bot duel: static bot, answer Yes", () -> {
			run(a, "botduel nokit");
			check("an unknown kit is refused", a.heard("no kit named 'nokit'"));
			run(a, "botduel testkit weird");
			check("an unknown bot type is refused and nothing starts", a.heard("The bot can be") && noDuel(a));
			run(a, "botduel testkit");
			check("with a kit name the question opens right away", menuHas(a, "Yes") && menuHas(a, "No"));
			menuStep(a, "Yes");
		}, () -> duel(a) != null, 100);
		step("bot duel: the duel with the static bot", () -> {
			Duel d = duel(a);
			check("a practice duel exists", d != null && d.practice && d.config.arena() == ArenaKind.BOT && d.config.unlimited(),
				d == null ? "no duel" : d.config.toString());
			if (d == null) return;
			bot = d.player(1) instanceof Dummy x ? x : null;
			check("the opponent is the bot", bot != null && bot.getName().getString().equals("Bot") && BotDuel.isBot(bot.getUUID()));
			if (bot == null) return;
			check("Yes made it a static bot", BotDuel.levelOf(bot.getUUID()) == BotLevel.STATIC);
			check("the bot is in the player list", server.getPlayerList().getPlayerByName("Bot") == bot);
			check("both are in the arena dimension", a.level().dimension().equals(Arena.DIMENSION) && bot.level().dimension().equals(Arena.DIMENSION),
				where(a) + " / " + where(bot));
			check("both have the kit sword", a.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && bot.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
			check("Alice's old marker item is gone during the duel", a.getInventory().getItem(21).isEmpty());
			check("Alice's inventory is saved on disk", PlayerBackup.exists(a.getUUID()));
			check("the invitation text names the bot arena", a.heard("Bot arena (radius 100)") && a.heard("Unlimited rounds"));
			check("the player is told what the bot does", a.heard("never fights back"));

			ServerLevel arena = Arena.level(server);
			int cx = Arena.botCenterX(d.slot);
			int fy = Arena.FLOOR_Y;
			check("floor: center, and the four corners at 100 blocks",
				arena.getBlockState(at(cx, fy, 0)).is(Blocks.BEDROCK) && arena.getBlockState(at(cx + 100, fy, 100)).is(Blocks.BEDROCK)
				&& arena.getBlockState(at(cx - 100, fy, -100)).is(Blocks.BEDROCK) && arena.getBlockState(at(cx + 100, fy, -100)).is(Blocks.BEDROCK)
				&& arena.getBlockState(at(cx - 100, fy, 100)).is(Blocks.BEDROCK));
			check("the floor ends at 100: nothing at 101 on the floor level except the wall",
				arena.getBlockState(at(cx + 101, fy, 0)).is(Blocks.BEDROCK) && arena.getBlockState(at(cx + 102, fy, 0)).isAir());
			check("the wall is 40 blocks high",
				arena.getBlockState(at(cx + 101, fy + 40, 0)).is(Blocks.BEDROCK) && arena.getBlockState(at(cx + 101, fy + 41, 0)).isAir()
				&& arena.getBlockState(at(cx, fy + 40, 101)).is(Blocks.BEDROCK) && arena.getBlockState(at(cx - 101, fy + 20, -101)).is(Blocks.BEDROCK));
			check("the inside is empty and there is void under the floor", arena.getBlockState(at(cx + 50, fy + 1, 50)).isAir()
				&& arena.getBlockState(at(cx, fy - 1, 0)).isAir());

			// Leave a mess behind in every kind of place; the next round must clean all of it.
			arena.setBlock(at(cx + 80, fy + 1, 70), Blocks.CHEST.defaultBlockState(), 3);
			arena.setBlock(at(cx + 60, fy, -40), Blocks.AIR.defaultBlockState(), 3);
			arena.setBlock(at(cx - 70, fy - 1, 30), Blocks.STONE.defaultBlockState(), 3);
			arena.setBlock(at(cx + 30, fy + 20, 30), Blocks.STONE.defaultBlockState(), 3);
			arena.setBlock(at(cx - 90, fy + 150, -60), Blocks.STONE.defaultBlockState(), 3);
			arena.setBlock(at(cx + 101, fy + 10, 0), Blocks.AIR.defaultBlockState(), 3);
			arena.setBlock(at(cx - 30, fy + 60, 90), Blocks.STONE.defaultBlockState(), 3);
			check("the mess is there", arena.getBlockState(at(cx - 90, fy + 150, -60)).is(Blocks.STONE) && arena.getBlockState(at(cx + 60, fy, -40)).isAir());
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);

		step("bot duel: the static bot is pushed away", () -> {
			Duel d = duel(a);
			double[] s = d.space.spawn(1);
			check("the bot stands at its spawn point", bot.distanceToSqr(s[0], s[1], s[2]) < 0.01, where(bot));
			check("Alice stands at hers", a.distanceToSqr(d.space.spawn(0)[0], d.space.spawn(0)[1], d.space.spawn(0)[2]) < 0.5, where(a));
			bot.setDeltaMovement(3.0, 0.5, 1.0);
			bot.teleportTo(Arena.level(server), s[0] + 6, s[1], s[2] + 3, java.util.Set.of(), 0f, 0f, true);
			botTick = ticks;
		}, () -> ticks - botTick > 6, 100);
		step("bot duel: the static bot is back where it was", () -> {
			Duel d = duel(a);
			double[] s = d.space.spawn(1);
			check("the bot cannot be moved", bot.distanceToSqr(s[0], s[1], s[2]) < 0.01 && bot.getDeltaMovement().lengthSqr() < 0.01, where(bot));
			check("still the same round with no points", d.phase == Duel.Phase.FIGHTING && d.points[0] == 0 && d.points[1] == 0);
			// the far corner is inside the arena
			int cx = Arena.botCenterX(d.slot);
			a.teleportTo(Arena.level(server), cx + 99.5, Arena.FLOOR_Y + 1, 99.5, java.util.Set.of(), 0f, 0f, true);
			botTick = ticks;
		}, () -> true, 1);
		step("bot duel: standing in the far corner is fine", () -> {}, () -> ticks - botTick > 20, 100);
		step("bot duel: hit 1", () -> {
			Duel d = duel(a);
			check("the far corner did not lose the round", d.phase == Duel.Phase.FIGHTING && d.points[1] == 0, d.phase + " " + d.points[0] + "-" + d.points[1]);
			softHit(a, bot, 3f);
			check("the first hit was counted", d.hits == 1 && d.damage > 0f && d.bestCombo == 1, d.hits + " hits, " + d.damage + " damage");
			botTick = ticks;
		}, () -> ticks - botTick > 3, 50);
		step("bot duel: hit 2", () -> {
			softHit(a, bot, 3f);
			botTick = ticks;
		}, () -> ticks - botTick > 3, 50);
		step("bot duel: hit 3 makes a combo", () -> {
			softHit(a, bot, 3f);
			Duel d = duel(a);
			check("three quick hits make a combo of 3", d.hits == 3 && d.bestCombo == 3, d.hits + " hits, best combo " + d.bestCombo);
			botTick = ticks;
		}, () -> ticks - botTick > 30, 100);
		step("bot duel: a late hit starts a new combo", () -> {
			softHit(a, bot, 3f);
			Duel d = duel(a);
			check("four hits, and the best combo is still 3", d.hits == 4 && d.bestCombo == 3, d.hits + " hits, best combo " + d.bestCombo);
			botTick = ticks;
		}, () -> ticks - botTick > 13, 100);
		step("bot duel: the sidebar shows the counters", () -> {
			check("the sidebar has hits, damage, combo and the bot type", sidebarHas(a, "Hits: 4") && sidebarHas(a, "Damage: ")
				&& sidebarHas(a, "Combo: 1 (best 3)") && sidebarHas(a, "Bot: Static"), String.valueOf(sidebar(a)));
			check("a static bot has no 'hits taken' line", !sidebarHas(a, "Hits taken"));
		});
		step("bot duel: Alice knocks the bot out", () -> {
			Duel d = duel(a);
			hit(a, bot);
			check("a KO is a point for Alice and nothing else changes", d.phase == Duel.Phase.FIGHTING && d.points[0] == 1 && d.points[1] == 0 && d.round == 1,
				d.phase + " " + d.points[0] + "-" + d.points[1] + " round " + d.round);
			check("the killing blow is counted too", d.hits == 5, String.valueOf(d.hits));
			check("the KO summary was sent", a.heard("KO 1: 5 hits"));
			check("the bot got up again at full health", bot.isAlive() && bot.getHealth() >= bot.getMaxHealth() - 0.01f, String.valueOf(bot.getHealth()));
			botTick = ticks;
		}, () -> ticks - botTick > 5, 50);
		step("bot duel: after the KO the practice goes on", () -> {
			Duel d = duel(a);
			check("still the same fight: no countdown, no new round", d.phase == Duel.Phase.FIGHTING && d.round == 1 && d.points[0] == 1,
				d.phase + " round " + d.round + " " + d.points[0] + "-" + d.points[1]);
			double[] s0 = d.space.spawn(1);
			check("the bot has not been moved to its spawn point by a reset", bot.distanceToSqr(s0[0], s0[1], s0[2]) < 0.01, where(bot));
			softHit(a, bot, 3f);
			check("it can be hit again", d.hits == 6, String.valueOf(d.hits));
			// drop out of the world: that does lose the round
			ServerLevel arena = Arena.level(server);
			a.teleportTo(arena, Arena.botCenterX(d.slot) + 0.5, Arena.FALL_Y - 10, 0.5, java.util.Set.of(), 0f, 0f, true);
		}, () -> true, 1);
		step("bot duel: falling loses the round", () -> {
			Duel d = duel(a);
			check("the bot got the point", d.points[1] == 1 && d.points[0] == 1, d.points[0] + "-" + d.points[1]);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING) && duel(a).round == 2, 600);
		step("bot duel: round 2 started clean", () -> {
			Duel d = duel(a);
			ServerLevel arena = Arena.level(server);
			int cx = Arena.botCenterX(d.slot);
			int fy = Arena.FLOOR_Y;
			check("the chest in the far corner is gone", arena.getBlockState(at(cx + 80, fy + 1, 70)).isAir());
			check("the hole in the floor is closed", arena.getBlockState(at(cx + 60, fy, -40)).is(Blocks.BEDROCK));
			check("the block under the floor is gone", arena.getBlockState(at(cx - 70, fy - 1, 30)).isAir());
			check("the block in the middle of the air is gone", arena.getBlockState(at(cx + 30, fy + 20, 30)).isAir());
			check("the block high above the walls is gone", arena.getBlockState(at(cx - 90, fy + 150, -60)).isAir());
			check("the block above the wall top but below the sky is gone", arena.getBlockState(at(cx - 30, fy + 60, 90)).isAir());
			check("the hole in the wall is closed", arena.getBlockState(at(cx + 101, fy + 10, 0)).is(Blocks.BEDROCK));
			double[] s = d.space.spawn(1);
			check("the bot is at its spawn point again", bot.distanceToSqr(s[0], s[1], s[2]) < 0.01, where(bot));
			check("the sidebar shows the score against the bot", sidebarHas(a, "vs Bot") && sidebarHas(a, "Alice: 1"), String.valueOf(sidebar(a)));
			check("the sidebar says unlimited", sidebarHas(a, "Unlimited"), String.valueOf(sidebar(a)));
		});
		step("bot duel: /leave closes everything", () -> {
			run(a, "leave");
			check("Alice is out of the duel", noDuel(a));
			check("she got her inventory back", a.getInventory().getItem(21).is(Items.STONE) && !a.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
			check("the backup is deleted", !PlayerBackup.exists(a.getUUID()));
			check("the practice total was shown", a.heard("Practice total: 6 hits"));
			check("the practice duel is not counted for the stats", StatsStore.get(a.getUUID()).duels == botStatsBefore
				&& StatsStore.get(a.getUUID()).elo == botEloBefore, StatsStore.get(a.getUUID()).duels + " vs " + botStatsBefore);
		}, () -> server.getPlayerList().getPlayerByName("Bot") == null, 200);
		step("bot duel: the bot is gone", () -> {
			check("no bot is left and no arena is in use", server.getPlayerList().getPlayerByName("Bot") == null && DuelManager.activeDuels() == 0,
				"duels=" + DuelManager.activeDuels());
			check("the bot's id is forgotten", !BotDuel.isBot(bot.getUUID()));
		});

		// ---------------------------------------------------------------- a bot that takes knockback
		step("passive bot: start from the command", () -> {
			run(a, "botduel testkit passive");
			Duel d = duel(a);
			check("a passive bot duel started", d != null && d.practice && BotDuel.levelOf(d.ids[1]) == BotLevel.PASSIVE);
			bot = d != null && d.player(1) instanceof Dummy x ? x : null;
			check("the bot was created", bot != null);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("passive bot: a push moves it", () -> {
			Duel d = duel(a);
			double[] s = d.space.spawn(1);
			check("it stands at its spawn point", bot.distanceToSqr(s[0], s[1], s[2]) < 0.04, where(bot));
			bot.setDeltaMovement(1.2, 0.3, 0.0);
			botX = bot.getX();
			botTick = ticks;
		}, () -> ticks - botTick > 25, 100);
		step("passive bot: it flew and landed", () -> {
			double moved = bot.getX() - botX;
			log("passive bot moved " + moved + " blocks; now at " + where(bot));
			check("a push moves a passive bot away", moved > 1.5, "moved " + moved);
			check("it landed on the floor of the arena", Math.abs(bot.getY() - (Arena.FLOOR_Y + 1)) < 0.2, where(bot));
			check("and it stopped", bot.getDeltaMovement().horizontalDistanceSqr() < 0.01, String.valueOf(bot.getDeltaMovement()));
			// A real blow, the way a player's game sends it (Player.attack): the push has to reach a bot that has no game.
			ServerLevel arena = Arena.level(server);
			a.teleportTo(arena, bot.getX() - 1.5, bot.getY(), bot.getZ(), java.util.Set.of(), -90f, 0f, true);
			bot.invulnerableTime = 0;
			botX = bot.getX();
			a.attack(bot);
			botTick = ticks;
		}, () -> ticks - botTick > 25, 100);
		step("passive bot: a hit knocks it back", () -> {
			double moved = bot.getX() - botX;
			log("a real blow moved the passive bot " + moved + " blocks");
			check("a blow from a player pushes it away from her", moved > 0.3, "moved " + moved);
			check("it was really hurt", bot.getHealth() < bot.getMaxHealth() || duel(a).hits >= 1, bot.getHealth() + " hp, hits " + duel(a).hits);
			check("a passive bot never fights back", duel(a).hitsTaken == 0);
			botTick = ticks;
		}, () -> ticks - botTick > 900, 1000);
		step("passive bot: the duel is still going after 45 seconds", () -> {
			Duel d = duel(a);
			check("an unlimited duel does not end by itself", d != null && !d.isClosedForTest() && server.getPlayerList().getPlayerByName("Bot") == bot,
				d == null ? "no duel" : d.phase + ", round " + d.round);
			check("it is still in its fighting phase", d != null && d.phase == Duel.Phase.FIGHTING, d == null ? "no duel" : String.valueOf(d.phase));
			run(a, "leave");
		}, () -> server.getPlayerList().getPlayerByName("Bot") == null, 200);

		// ---------------------------------------------------------------- a bot that fights back
		step("hard bot: start from the command", () -> {
			run(a, "botduel testkit hard");
			Duel d = duel(a);
			check("a hard bot duel started", d != null && d.practice && BotDuel.levelOf(d.ids[1]) == BotLevel.HARD);
			bot = d != null && d.player(1) instanceof Dummy x ? x : null;
			check("the bot was created", bot != null);
			if (bot != null) log("hard bot starts at " + where(bot) + ", Alice at " + where(a));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("hard bot: it walks up to Alice and hits her", () -> {}, () -> duel(a) != null && duel(a).hitsTaken >= 1, 400);
		step("hard bot: it was next to Alice", () -> {
			Duel d = duel(a);
			log("hard bot at " + where(bot) + ", Alice at " + where(a) + ", hits taken " + d.hitsTaken);
			check("the bot hit Alice", d.hitsTaken >= 1 && d.damageTaken > 0f, "hits taken " + d.hitsTaken);
			check("the bot came close to Alice", bot.distanceTo(a) < 5.0, "distance " + bot.distanceTo(a));
			botTick = ticks;
		}, () -> ticks - botTick > 900, 1000);
		step("hard bot: the duel is still going after 45 seconds", () -> {
			Duel d = duel(a);
			check("an unlimited duel against a fighting bot does not end by itself", d != null && server.getPlayerList().getPlayerByName("Bot") == bot,
				d == null ? "no duel" : d.phase + ", round " + d.round);
			log("after 45 s: round " + (d == null ? -1 : d.round) + ", Alice " + (d == null ? "" : d.points[0]) + " - Bot " + (d == null ? "" : d.points[1]));
			run(a, "leave");
		}, () -> server.getPlayerList().getPlayerByName("Bot") == null, 200);
		step("bots: all gone", () -> {
			check("no bot is left", server.getPlayerList().getPlayerByName("Bot") == null && DuelManager.activeDuels() == 0);
			check("and Alice's stats still don't count the practice", StatsStore.get(a.getUUID()).duels == botStatsBefore);
		});

		step("statistics, sidebar and guide book", () -> {
			StatsStore.Stats sa = StatsStore.get(a.getUUID());
			StatsStore.Stats sb = StatsStore.get(b.getUUID());
			check("Alice's wins and losses were counted", sa.wins >= 3 && sa.losses >= 1 && sa.duels == sa.wins + sa.losses + sa.draws,
				sa.wins + "W " + sa.losses + "L of " + sa.duels);
			check("Bob's wins were counted", sb.wins >= 1 && sb.losses >= 1, sb.wins + "W " + sb.losses + "L");
			check("the best streak is at least the streak", sa.bestStreak >= sa.streak && sa.bestStreak >= 1);
			check("the ratings of two players who only played each other add up to 2000", sa.elo + sb.elo == 2000, sa.elo + " + " + sb.elo);
			check("Alice, who won more, has the higher rating", sa.wins > sb.wins ? sa.elo > sb.elo : true, sa.elo + " vs " + sb.elo);
			run(a, "top");
			check("/top shows the ranking with both players", a.heard("Top 2 by Elo") && a.heard(" 1. ") && a.heard(" 2. ") && a.heard("You: #"));
			run(a, "top wins");
			check("/top wins works", a.heard("Top 2 by wins"));
			run(a, "top streak");
			check("/top streak works", a.heard("Top 2 by best win streak"));
			run(a, "top nonsense");
			check("a wrong ranking name is refused", a.heard("Use /top"));
			run(a, "sidebar off");
			check("/sidebar off hides it", sidebar(a).isEmpty());
			run(a, "sidebar on");
			check("/sidebar on shows her stats again", sidebarHas(a, "Wins: " + sa.wins), String.valueOf(sidebar(a)));
			check("the sidebar shows the Elo rating", sidebarHas(a, "Elo: " + sa.elo), String.valueOf(sidebar(a)));
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

		// ---------------------------------------------------------------- queue and the ranking per kit
		step("queue: setup", () -> {
			StatsStore.resetKitsForTest();
			Queue.clearAll();
			Queue.useRandom(new java.util.Random(7));
			a.getInventory().clearContent();
			a.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			a.getInventory().setItem(1, new ItemStack(Items.GOLDEN_APPLE, 4));
			run(a, "kit save qkit");
			a.getInventory().clearContent();
			a.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
			run(a, "kit save qkit-b");
			a.getInventory().clearContent();
			a.getInventory().setItem(21, new ItemStack(Items.STONE, 3));
			b.getInventory().clearContent();
			b.getInventory().setItem(0, new ItemStack(Items.NETHERITE_SWORD));
			run(b, "kit save bqkit");
			b.getInventory().clearContent();
			b.getInventory().setItem(20, new ItemStack(Items.DIRT, 5));
			run(a, "publicate qkit");
			check("the kits exist", KitStore.exists(a.getUUID(), "qkit") && KitStore.exists(a.getUUID(), "qkit-b") && KitStore.exists(b.getUUID(), "bqkit"));
			check("qkit is on the public shelf", PublicKits.find("qkit").isPresent());
			check("nobody is in a queue", Queue.size() == 0);
		});

		step("queue: /queue opens the menu with the three queues", () -> {
			run(a, "queue");
			check("the menu has the public queue", menuHas(a, "Public queue"));
			check("the menu has the random queue", menuHas(a, "Random queue"));
			check("the menu has the own kit queue", menuHas(a, "Own kit queue"));
			check("there is no leave button while she is not in a queue", !menuHas(a, "Leave the queue"));
			menuStep(a, "Public queue");
		});
		step("queue: the public kit menu", () -> {
			check("it offers a random public kit", menuHas(a, "Random public kit"));
			check("it offers qkit", menuHas(a, "qkit"));
			menuStep(a, "qkit");
		});
		step("queue: Alice waits in the public queue", () -> {
			check("Alice is in the queue", Queue.isQueued(a.getUUID()));
			check("she was told, with a leave button", a.heard("You joined the public (qkit) queue") && a.heard("[LEAVE QUEUE]"));
			check("the menu closed", !(a.containerMenu instanceof ChestMenu));
			run(a, "queue list");
			check("/queue list shows her", a.heard("Alice: public (qkit)"));
			run(a, "queue public nothere");
			check("an unknown public kit is refused and she keeps waiting", a.heard("There is no public kit named 'nothere'") && Queue.isQueued(a.getUUID()));
			run(a, "queue own nothere");
			check("an unknown own kit is refused", a.heard("You have no kit named 'nothere'"));
			run(a, "queue");
			check("the menu offers to leave now", menuHas(a, "Leave the queue"));
			a.closeContainer();
		});
		step("queue: a different queue does not match", () -> {
			run(b, "queue random");
			check("Bob is in the random queue", Queue.isQueued(b.getUUID()));
			check("both wait and there is no duel", Queue.size() == 2 && duel(a) == null && duel(b) == null);
			run(b, "queue leave");
			check("Bob left", !Queue.isQueued(b.getUUID()) && b.heard("You left the random queue"));
			run(b, "queue leave");
			check("leaving twice says so", b.heard("You are not in a queue"));
			run(b, "queue public bqkit");
			check("a kit that is not public can't be picked there", b.heard("There is no public kit named 'bqkit'") && !Queue.isQueued(b.getUUID()));
			run(carl, "queue take Nobody");
			check("taking a kit nobody offers is refused", carl.heard("Nobody is not waiting with a kit"));
		});
		step("queue: Bob picks the same public kit and the duel starts", () -> {
			run(b, "queue public qkit");
			check("both left the queue", !Queue.isQueued(a.getUUID()) && !Queue.isQueued(b.getUUID()));
			Duel d = duel(a);
			check("a duel exists for both", d != null && duel(b) == d);
			check("they were told", a.heard("Match found!") && b.heard("Match found!"));
			check("the kit is qkit", d != null && d.config.kit().equals("qkit") && d.kitOwner.equals(a.getUUID()));
			check("flat arena with walls, first to 3", d != null && d.config.arena() == ArenaKind.FLAT && d.config.walls()
				&& d.config.mode() == WinMode.FIRST_TO && d.config.limit() == 3);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("queue: the duel runs with the public kit; nobody can queue in a duel", () -> {
			check("Bob got the sword of the kit", b.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
			run(a, "queue random");
			check("somebody in a duel can't join a queue", a.heard("spectator mode first") && !Queue.isQueued(a.getUUID()));
			run(a, "leave");
		}, () -> noDuel(b), 600);
		step("queue: the ranking of the kit", () -> {
			StatsStore.Stats sa = StatsStore.getKit("qkit", a.getUUID());
			StatsStore.Stats sb = StatsStore.getKit("qkit", b.getUUID());
			check("Bob won with the kit and gained 16", sb.wins == 1 && sb.duels == 1 && sb.elo == 1016, sb.wins + "W elo " + sb.elo);
			check("Alice lost with it and lost 16", sa.losses == 1 && sa.duels == 1 && sa.elo == 984, sa.losses + "L elo " + sa.elo);
			check("another kit has no numbers", StatsStore.getKit("qkit-b", a.getUUID()).duels == 0);
			check("the duel told them the Elo with the kit", b.heard("Elo with kit qkit: 1000 -> 1016") && a.heard("Elo with kit qkit: 1000 -> 984"));
			run(a, "top kit");
			check("/top kit lists qkit", a.heard("Kits with a ranking") && a.heard("/top kit qkit"));
			run(a, "top kit qkit");
			check("/top kit qkit shows both players", a.heard("Top 2 by Elo with kit qkit") && a.heard("You: #2 of 2"));
			run(a, "top kit QKIT wins");
			check("/top kit works with wins and any case", a.heard("Top 2 by wins with kit qkit"));
			run(a, "top kit nothere");
			check("a kit nobody played says so", a.heard("Nobody has played a ranked duel with the kit 'nothere'"));
			run(a, "top kit qkit nonsense");
			check("a wrong ranking name is refused", a.heard("Use /top kit qkit"));
		});

		step("queue: where the random kit can come from", () -> {
			run(dora, "queue own dk1");
			check("Dora waits with dk1", Queue.isQueued(dora.getUUID()) && dora.heard("Waiting for somebody to take your kit 'dk1'"));
			java.util.Set<String> owners = new java.util.HashSet<>();
			boolean shelf = false;
			for (int i = 0; i < 300; i++) {
				Queue.Choice c = Queue.randomChoice(a, b);
				if (c == null) continue;
				if (c.source().contains("public kit")) shelf = true;
				else owners.add(c.owner().toString());
			}
			check("it can be a public kit", shelf);
			check("it can be one of Alice's", owners.contains(a.getUUID().toString()));
			check("it can be one of Bob's", owners.contains(b.getUUID().toString()));
			check("it can be one offered in the own kit queue", owners.contains(dora.getUUID().toString()));
			run(dora, "queue leave");
			check("Dora left", !Queue.isQueued(dora.getUUID()));
		});
		step("queue: the random queue matches two players", () -> {
			Queue.useRandom(new java.util.Random(11));
			run(a, "queue random");
			check("Alice waits", Queue.isQueued(a.getUUID()) && duel(a) == null);
			run(b, "queue random");
			Duel d = duel(a);
			check("they got a duel", d != null && duel(b) == d && !Queue.isQueued(a.getUUID()) && !Queue.isQueued(b.getUUID()));
			check("the kit came from the random queue", a.heard("random queue:") && b.heard("random queue:"));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("queue: the random duel ends", () -> {
			check("both have a kit in their hands", !a.getInventory().getItem(0).isEmpty() || !a.getInventory().getItem(1).isEmpty());
			run(b, "leave");
		}, () -> noDuel(a), 600);

		step("queue: the own kit queue, Alice offers a kit", () -> {
			run(a, "queue");
			menuStep(a, "Own kit queue");
		});
		step("queue: nobody offers anything yet", () -> {
			check("it says so", menuHas(a, "Nobody is offering a kit right now"));
			menuStep(a, "Offer one of my kits");
		});
		step("queue: Alice picks the kit to offer", () -> {
			check("her kits are listed", menuHas(a, "qkit") && menuHas(a, "qkit-b"));
			menuStep(a, "qkit");
		});
		step("queue: Bob sees the offer", () -> {
			Queue.Entry offer = Queue.entryOf(a.getUUID());
			check("Alice waits with qkit in the own kit queue", offer != null && offer.mode() == Queue.Mode.OWN && "qkit".equals(offer.kit()));
			run(b, "queue");
			menuStep(b, "Own kit queue");
		});
		step("queue: Bob takes the kit and they fight with it", () -> {
			check("Bob sees Alice's kit", menuHas(b, "Alice's kit: qkit"));
			menuStep(b, "Alice's kit: qkit");
			Duel d = duel(a);
			check("they got a duel", d != null && duel(b) == d && !Queue.isQueued(a.getUUID()));
			check("with Alice's kit", d != null && d.config.kit().equals("qkit") && d.kitOwner.equals(a.getUUID()));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("queue: the own kit duel ends", () -> {
			check("Bob has Alice's sword", b.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
			run(b, "leave");
		}, () -> noDuel(a), 600);
		step("queue: both duels counted for the kit", () -> {
			StatsStore.Stats sa = StatsStore.getKit("qkit", a.getUUID());
			StatsStore.Stats sb = StatsStore.getKit("qkit", b.getUUID());
			check("the kit has more duels now", sa.duels >= 2 && sb.duels >= 2, sa.duels + " / " + sb.duels);
			check("the Elo with the kit adds up to 2000", sa.elo + sb.elo == 2000, sa.elo + " + " + sb.elo);
		});

		step("queue: back in the lobby", () -> {
			a.teleportTo((ServerLevel) a.level(), 60.5, a.getY(), 55.5, java.util.Set.of(), 0f, 0f, true);
		}, () -> LobbyKit.isActive(a.getUUID()), 200);
		step("queue: the Leave barrier takes her out of the queue", () -> {
			run(a, "queue public qkit");
			check("Alice waits", Queue.isQueued(a.getUUID()));
			a.getInventory().setSelectedSlot(1);
			LobbyKit.clearCooldowns();
			a.gameMode.useItem(a, (ServerLevel) a.level(), a.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
			check("she is not in the queue any more", !Queue.isQueued(a.getUUID()));
			check("she was told", a.heard("You left the public (qkit) queue"));
			a.getInventory().setSelectedSlot(3);
		});
		step("queue: clean up", () -> {
			Queue.clearAll();
			Queue.useRandom(new java.util.Random());
			check("nobody is left in a queue", Queue.size() == 0);
		});

		step("kits: every item of the game is saved and read back the same", () -> {
			var inv = a.getInventory();
			List<ItemStack> all = new ArrayList<>();
			for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				ItemStack stack = new ItemStack(item, item.getDefaultMaxStackSize());
				if (!stack.isEmpty()) all.add(stack);
			}
			// Items with extra data, like the ones people make in the workshop.
			ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
			named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("My Sword"));
			named.set(net.minecraft.core.component.DataComponents.LORE,
				new net.minecraft.world.item.component.ItemLore(List.of(Component.literal("line one"), Component.literal("line two"))));
			named.set(net.minecraft.core.component.DataComponents.DAMAGE, 17);
			all.add(named);
			ItemStack pearls = new ItemStack(Items.ENDER_PEARL, 64);
			pearls.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64);
			all.add(pearls);
			ItemStack totems = new ItemStack(Items.TOTEM_OF_UNDYING, 99);
			totems.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 99);
			all.add(totems);

			int batches = 0;
			List<String> bad = new ArrayList<>();
			for (int from = 0; from < all.size(); from += 41) {
				inv.clearContent();
				for (int i = 0; i < 41 && from + i < all.size(); i++) inv.setItem(i, all.get(from + i).copy());
				batches++;
				try {
					KitStore.SaveReport report = KitStore.save(server, a, "alltest");
					for (String w : report.warnings()) bad.add("batch " + batches + ": " + w);
					var kit = KitStore.read(server, a.getUUID(), "alltest");
					if (kit.isEmpty()) {
						bad.add("batch " + batches + ": the kit file disappeared");
						continue;
					}
					for (int i = 0; i < 41; i++) {
						ItemStack live = inv.getItem(i);
						if (!live.isEmpty() && !ItemStack.matches(live, kit.get().slots()[i])) {
							bad.add("batch " + batches + " slot " + i + ": " + live + " came back as " + kit.get().slots()[i]);
						}
					}
				} catch (IOException | RuntimeException e) {
					bad.add("batch " + batches + ": " + e);
				}
			}
			log("kit round trip: " + all.size() + " stacks in " + batches + " batches, " + bad.size() + " problems");
			for (int i = 0; i < Math.min(bad.size(), 25); i++) log("  kit problem: " + bad.get(i));
			check("all " + all.size() + " item stacks of the game are saved and come back identical", bad.isEmpty(),
				bad.isEmpty() ? "" : bad.size() + " problems, first: " + bad.get(0));
			try {
				KitStore.delete(a.getUUID(), "alltest");
			} catch (IOException ignored) {
				// the test folder is thrown away anyway
			}
			for (int i = 0; i < 41; i++) inv.setItem(i, ItemStack.EMPTY);
		});

		step("kits: /kit save says what it saved and never writes a blank kit", () -> {
			var inv = a.getInventory();
			inv.clearContent();
			run(a, "kit save blanktest");
			check("an empty inventory is not saved as a blank kit", !KitStore.exists(a.getUUID(), "blanktest") && a.heard("inventory is empty"));

			inv.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			inv.setItem(1, new ItemStack(Items.GOLDEN_APPLE, 5));
			a.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			run(a, "kit save goodkit");
			check("/kit save tells how many stacks are in the file (3)", a.heard("3 item stacks in the file"));

			inv.clearContent();
			a.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			run(a, "kit save goodkit");
			try {
				var kit = KitStore.read(server, a.getUUID(), "goodkit");
				check("an empty inventory does not wipe a good kit", kit.isPresent() && kit.get().stacks() == 3 && a.heard("stays as it was"),
					kit.isPresent() ? "stacks=" + kit.get().stacks() : "no kit");
			} catch (IOException e) {
				check("the kit can be read", false, e.toString());
			}
		});

		step("kits: /kit show opens the kit as a look-only chest", () -> {
			run(a, "kit show goodkit");
		});
		step("kits: the chest shows the kit and nothing can be taken", () -> {
			AbstractContainerMenu menu = a.containerMenu;
			boolean chest = menu instanceof ChestMenu c && c.getRowCount() == 6;
			check("a 6-row chest opened", chest);
			if (!chest) return;
			check("the hotbar sword is in the bottom row", menu.getSlot(KitView.where(0)).getItem().is(Items.DIAMOND_SWORD));
			check("the apples keep their count", menu.getSlot(KitView.where(1)).getItem().getCount() == 5);
			check("the helmet is in the armor corner", menu.getSlot(KitView.where(39)).getItem().is(Items.IRON_HELMET));
			menu.clicked(KitView.where(0), 0, ContainerInput.PICKUP, a);
			check("clicking an item does not take it", a.containerMenu.getCarried().isEmpty() && a.getInventory().getItem(0).isEmpty());
			a.closeContainer();
			check("the kit file was not touched by looking", KitStore.exists(a.getUUID(), "goodkit"));
		});

		step("kits: /kit load puts the whole kit in the game of the player", () -> {
			a.getInventory().clearContent();
			a.getInventory().setItem(30, new ItemStack(Items.STONE, 7));
			run(a, "kit load goodkit");
			check("the kit replaced the inventory", a.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && a.getInventory().getItem(30).isEmpty()
				&& a.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET));
			check("/kit load tells how many stacks it loaded", a.heard("Loaded kit 'goodkit' (3 item stacks)"));
		});
		step("kits: the game of the player shows the loaded kit", () -> {
			check("her game was sent the whole kit", clientMatches(a), clientDiff(a));
		});

		step("kits: a stack that is too big or unreadable is rescued, not lost", () -> {
			var inv = a.getInventory();
			inv.clearContent();
			inv.setItem(3, new ItemStack(Items.DIAMOND_SWORD, 5));
			run(a, "kit save bigstack");
			try {
				var kit = KitStore.read(server, a.getUUID(), "bigstack");
				check("a stack bigger than its maximum still ends up in the kit", kit.isPresent() && kit.get().slots()[3].is(Items.DIAMOND_SWORD),
					kit.isPresent() ? String.valueOf(kit.get().slots()[3]) : "no kit");
				log("over-full stack: " + (kit.isPresent() ? kit.get().slots()[3] : "no kit") + " / told: " + a.heard("not saved exactly"));
			} catch (IOException e) {
				check("the kit can be read", false, e.toString());
			}

			com.google.gson.JsonObject root = new com.google.gson.JsonObject();
			com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
			for (int i = 0; i < 41; i++) slots.add(new com.google.gson.JsonObject());
			com.google.gson.JsonObject broken = com.google.gson.JsonParser.parseString(
				"{\"id\":\"minecraft:diamond_sword\",\"count\":1,\"components\":{\"minecraft:damage\":-5}}").getAsJsonObject();
			slots.set(0, broken);
			slots.set(5, com.google.gson.JsonParser.parseString("{\"id\":\"minecraft:not_an_item\",\"count\":1}"));
			slots.set(6, com.google.gson.JsonParser.parseString("{\"id\":\"minecraft:golden_apple\",\"count\":3}"));
			root.addProperty("name", "damaged");
			root.add("slots", slots);
			try {
				KitStore.writeRaw(a.getUUID(), "damaged", root);
				var kit = KitStore.read(server, a.getUUID(), "damaged");
				check("a kit with a broken stack still loads the rest", kit.isPresent() && kit.get().slots()[6].is(Items.GOLDEN_APPLE)
					&& kit.get().slots()[6].getCount() == 3);
				check("the item with broken data comes back as the plain item", kit.isPresent() && kit.get().slots()[0].is(Items.DIAMOND_SWORD),
					kit.isPresent() ? String.valueOf(kit.get().slots()[0]) : "no kit");
				check("an item that does not exist is reported", kit.isPresent() && kit.get().problems().stream().anyMatch(t -> t.startsWith("hotbar 6")),
					kit.isPresent() ? String.valueOf(kit.get().problems()) : "no kit");
				run(a, "kit load damaged");
				check("/kit load tells which slots could not be read", a.heard("could not be read exactly"));
			} catch (IOException e) {
				check("the kit can be written and read", false, e.toString());
			}
			for (int i = 0; i < 41; i++) inv.setItem(i, ItemStack.EMPTY);
			inv.setItem(21, new ItemStack(Items.STONE, 3));
		});

		step("kits: items taken in the creative menu are in the saved kit", () -> {
			run(a, "kit create");
			check("Alice is in the workshop", Workshop.isIn(a.getUUID()));
			// What the game of a player sends when they pick an item from the creative menu (menu slot 36 = hotbar 1).
			a.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket((short) 36, new ItemStack(Items.NETHERITE_SWORD)));
			a.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket((short) 37, new ItemStack(Items.GOLDEN_APPLE, 64)));
			a.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket((short) 5, new ItemStack(Items.NETHERITE_HELMET)));
			a.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket((short) 45, new ItemStack(Items.SHIELD)));
			check("the server took the creative picks (sword, apples, helmet, shield)", a.getInventory().getItem(0).is(Items.NETHERITE_SWORD)
				&& a.getInventory().getItem(1).getCount() == 64 && a.getItemBySlot(EquipmentSlot.HEAD).is(Items.NETHERITE_HELMET)
				&& a.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD), String.valueOf(a.getInventory().getItem(0)));
			run(a, "kit save creative1");
			check("/kit save counts 4 stacks", a.heard("4 item stacks in the file"));
			try {
				var kit = KitStore.read(server, a.getUUID(), "creative1");
				check("the kit has the creative picks", kit.isPresent() && kit.get().slots()[0].is(Items.NETHERITE_SWORD) && kit.get().stacks() == 4,
					kit.isPresent() ? "stacks=" + kit.get().stacks() : "no kit");
			} catch (IOException e) {
				check("the kit can be read", false, e.toString());
			}
		});
		step("kits: leaving the workshop after the creative picks", () -> {
			run(a, "leave");
			check("Alice left the workshop", !Workshop.isIn(a.getUUID()));
		});

		step("armor: Sort puts on the best armor and never a Curse of Binding piece", () -> {
			run(a, "kit create");
			check("Alice is in the workshop", Workshop.isIn(a.getUUID()));
			var inv = a.getInventory();
			for (int i = 0; i < 41; i++) inv.setItem(i, ItemStack.EMPTY);

			inv.setItem(0, new ItemStack(Items.IRON_HELMET));
			inv.setItem(1, new ItemStack(Items.DIAMOND_HELMET));
			inv.setItem(2, new ItemStack(Items.LEATHER_HELMET));
			// a netherite chestplate with Curse of Binding: better than the diamond one, but must not be put on
			inv.setItem(3, new ItemStack(Items.NETHERITE_CHESTPLATE));
			inv.setSelectedSlot(3);
			run(a, "enchant binding_curse 1");
			check("the netherite chestplate got Curse of Binding", a.getMainHandItem().getEnchantments().toString().contains("binding_curse"));
			inv.setItem(4, new ItemStack(Items.DIAMOND_CHESTPLATE));
			inv.setItem(5, new ItemStack(Items.GOLDEN_BOOTS));
			inv.setItem(6, new ItemStack(Items.IRON_BOOTS));
			inv.setItem(7, new ItemStack(Items.ELYTRA));
			// worn diamond leggings with Curse of Binding: a better pair in the backpack must not replace them
			inv.setItem(8, new ItemStack(Items.DIAMOND_LEGGINGS));
			inv.setSelectedSlot(8);
			run(a, "enchant binding_curse 1");
			a.setItemSlot(EquipmentSlot.LEGS, inv.getItem(8).copy());
			inv.setItem(8, new ItemStack(Items.CARVED_PUMPKIN));
			inv.setItem(11, new ItemStack(Items.NETHERITE_LEGGINGS));
			inv.setSelectedSlot(0);

			run(a, "layout sort");
			check("the best helmet (diamond) is on", a.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), String.valueOf(a.getItemBySlot(EquipmentSlot.HEAD)));
			check("the diamond chestplate is on, not the cursed netherite one", a.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
				String.valueOf(a.getItemBySlot(EquipmentSlot.CHEST)));
			check("the worn cursed leggings were not replaced", a.getItemBySlot(EquipmentSlot.LEGS).is(Items.DIAMOND_LEGGINGS)
				&& inv.getItem(11).is(Items.NETHERITE_LEGGINGS), String.valueOf(a.getItemBySlot(EquipmentSlot.LEGS)));
			check("the best boots (iron) are on", a.getItemBySlot(EquipmentSlot.FEET).is(Items.IRON_BOOTS), String.valueOf(a.getItemBySlot(EquipmentSlot.FEET)));
			check("nothing was lost: the helmet slot got swapped (the iron one is still in the inventory)", inv.getItem(0).is(Items.IRON_HELMET));
			boolean elytraKept = false;
			boolean pumpkinKept = false;
			for (int i = 0; i < 36; i++) {
				if (inv.getItem(i).is(Items.ELYTRA)) elytraKept = true;
				if (inv.getItem(i).is(Items.CARVED_PUMPKIN)) pumpkinKept = true;
			}
			check("elytra and pumpkin are never worn by Sort", elytraKept && pumpkinKept && !a.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
				&& !a.getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN));
			check("it says what it put on", a.heard("Armor: put on"));
			check("it says the cursed piece was left out", a.heard("Curse of Binding"));

			a.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
			run(a, "layout sort");
			check("a second Sort puts on the remaining boots (the golden ones)", a.getItemBySlot(EquipmentSlot.FEET).is(Items.GOLDEN_BOOTS),
				String.valueOf(a.getItemBySlot(EquipmentSlot.FEET)));
			run(a, "layout sort");
			check("Sort changes nothing more when the best armor is already on",
				a.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET) && a.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE));
		});
		step("armor: /layout armor off, a better worn piece and Protection", () -> {
			var inv = a.getInventory();
			run(a, "layout armor off");
			check("/layout armor off says so", a.heard("only sorts your inventory"));
			a.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
			run(a, "layout sort");
			check("with it off Sort leaves your armor alone", a.getItemBySlot(EquipmentSlot.FEET).isEmpty());
			run(a, "layout armor on");
			check("/layout armor on says so", a.heard("also puts on the best armor"));

			// worn netherite helmet beats the diamond one in the backpack
			a.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
			inv.setItem(20, new ItemStack(Items.DIAMOND_HELMET));
			// diamond boots with Protection beat plain netherite boots? score: diamond 3+1+protection IV(4)... check both ways
			run(a, "layout sort");
			check("a better worn piece is kept", a.getItemBySlot(EquipmentSlot.HEAD).is(Items.NETHERITE_HELMET));
			check("and the worse one stays in the backpack", inv.getItem(20).is(Items.DIAMOND_HELMET));

			inv.setItem(1, new ItemStack(Items.DIAMOND_HELMET));
			inv.setSelectedSlot(1);
			run(a, "enchant protection 10");
			inv.setSelectedSlot(0);
			run(a, "layout sort");
			check("a helmet with Protection X beats a plain netherite one", a.getItemBySlot(EquipmentSlot.HEAD).getEnchantments().toString().contains("protection"),
				String.valueOf(a.getItemBySlot(EquipmentSlot.HEAD)));

			run(a, "leave");
			check("Alice left the workshop", !Workshop.isIn(a.getUUID()));
			check("her armor slots are back to what she had", a.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
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
