package com.pvpduels.duel;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.GuideBook;
import com.pvpduels.duel.TestDummy.Dummy;
import com.pvpduels.achievements.Achievements;
import com.pvpduels.arena.ArenaData;
import com.pvpduels.arena.ArenaStore;
import com.pvpduels.arena.ArenaStudio;
import com.pvpduels.arena.BuilderRank;
import com.pvpduels.arena.Contest;
import com.pvpduels.effects.Effects;
import com.pvpduels.effects.Missions;
import com.pvpduels.effects.Particles;
import com.pvpduels.effects.Victory;
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
import net.minecraft.stats.Stats;
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

	/** The test world is a superflat with the usual mob spawning: a slime once killed a fake player in the middle of the test. */
	private static void clearMonsters() {
		for (ServerLevel level : server.getAllLevels()) {
			for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
				if (e instanceof net.minecraft.world.entity.monster.Enemy) e.discard();
			}
		}
	}

	private static void tick() {
		ticks++;
		if (ticks % 10 == 0) clearMonsters();
		if (ticks > 20 * 800) {
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
	private static final long CONTEST_T0 = 1_800_000_000_000L;
	private static final long CONTEST_DAY = 24L * 3600_000;
	private static Dummy a;
	private static Dummy b;
	private static final double[] aPos = new double[3];
	private static final double[] bPos = new double[3];

	private static int circleCx;
	private static int ruleStartTick;
	private static float ruleStartHealth;
	private static int carlTick;
	private static int studioCx;
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

	private static boolean titleHeard(Dummy who, String text) {
		for (Packet<?> packet : List.copyOf(who.link.sent)) {
			if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket title && title.text().getString().contains(text)) return true;
		}
		return false;
	}

	private static int packetCount(Dummy who, String simpleName) {
		int n = 0;
		for (Packet<?> packet : List.copyOf(who.link.sent)) if (packet.getClass().getSimpleName().equals(simpleName)) n++;
		return n;
	}

	/** How many entities of the arena world have a tag that starts like this. */
	private static int taggedCount(String prefix) {
		int n = 0;
		ServerLevel level = Arena.level(server);
		if (level == null) return 0;
		for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
			for (String tag : e.entityTags()) if (tag.startsWith(prefix)) n++;
		}
		return n;
	}

	private static int particlePackets(Dummy who) {
		int n = 0;
		for (Packet<?> packet : who.link.sent) if (packet.getClass().getSimpleName().equals("ClientboundLevelParticlesPacket")) n++;
		return n;
	}

	/** How many items of the open menu have a name that starts like this. */
	private static int menuCount(Dummy who, String prefix) {
		AbstractContainerMenu menu = who.containerMenu;
		if (!(menu instanceof ChestMenu chest)) return -1;
		int n = 0;
		for (int i = 0; i < chest.getRowCount() * 9; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (!stack.isEmpty() && stack.getHoverName().getString().startsWith(prefix)) n++;
		}
		return n;
	}

	private static ItemStack menuItem(Dummy who, String name) {
		AbstractContainerMenu menu = who.containerMenu;
		if (!(menu instanceof ChestMenu chest)) return ItemStack.EMPTY;
		for (int i = 0; i < chest.getRowCount() * 9; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (!stack.isEmpty() && stack.getHoverName().getString().equals(name)) return stack;
		}
		return ItemStack.EMPTY;
	}

	/** The error texts that are not null, one after another (empty when everything went well). */
	private static String join(String... results) {
		StringBuilder out = new StringBuilder();
		for (String r : results) if (r != null) out.append(r).append(' ');
		return out.toString();
	}

	private static double dist(Dummy p, double[] pos) {
		return Math.sqrt(p.distanceToSqr(pos[0], pos[1], pos[2]));
	}

	private static boolean phaseIs(Dummy who, Duel.Phase phase) {
		Duel d = duel(who);
		return d != null && d.phase == phase;
	}

	/** A duel in this round of the match and in the fighting phase. */
	private static boolean roundIs(Dummy who, int round) {
		Duel d = duel(who);
		return d != null && d.round == round && d.phase == Duel.Phase.FIGHTING;
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
		// The finale of a win (camera, doubles) has its own steps; the older steps see the plain end of a duel.
		Cinema.enabled = false;
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
			check("y70 is grass", isBlock(x, 70, 0, Blocks.GRASS_BLOCK));
			check("y69 is dirt", isBlock(x, 69, 0, Blocks.DIRT));
			check("y66 is dirt (4 blocks of dirt under the grass)", isBlock(x, 66, 0, Blocks.DIRT));
			check("y65 is stone", isBlock(x, 65, 0, Blocks.STONE));
			check("y0 is stone", isBlock(x, 0, 0, Blocks.STONE));
			check("y-1 is deepslate", isBlock(x, -1, 0, Blocks.DEEPSLATE));
			check("y-59 is deepslate", isBlock(x, -59, 0, Blocks.DEEPSLATE));
			check("y-60 is bedrock", isBlock(x, -60, 0, Blocks.BEDROCK));
			check("y-61 is air", isBlock(x, -61, 0, Blocks.AIR));
			check("y71 is air", isBlock(x, 71, 0, Blocks.AIR));
			check("the players stand on the grass", Math.abs(a.getY() - 71) < 0.5 && Math.abs(b.getY() - 71) < 0.5, where(a) + " / " + where(b));
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
			check("the dug hole is filled again", isBlock(circleCx - 16, 70, 1, Blocks.GRASS_BLOCK), String.valueOf(Terrain.level(server).getBlockState(at(circleCx - 16, 70, 1))));
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
			check("digging is refused", !dug && lvl.getBlockState(ground.south()).is(Blocks.GRASS_BLOCK));

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
				for (String word : new String[] {"/duels", "/npc", "/queue", "/top kit", "Send Duel", "Create+", "/botduel", "/kit save", "/layout", "/top", "/spectate", "About this server", "/effects", "/missions"}) {
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
			check("somebody who is not a Builder sees why they can't", a.containerMenu instanceof ChoiceMenu && menuHas(a, "Builders only"));
			a.closeContainer();
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
					case ARENA -> check("the Arena Builder opens the arena menu", a.containerMenu instanceof ChoiceMenu && menuHas(a, "Builders only"));
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
			check("it is never one offered in the own kit queue by somebody else", !owners.contains(dora.getUUID().toString()));
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
			// the lobby of the earlier tests was cleared: make a new one where Alice stands
			a.teleportTo(server.overworld(), 50.5, a.getY(), 50.5, java.util.Set.of(), 0f, 0f, true);
			try {
				Lobby.set(a);
			} catch (java.io.IOException e) {
				check("the lobby can be saved", false, e.toString());
			}
			check("the lobby is set", Lobby.isSet());
		}, () -> {
			boolean ok = LobbyKit.isActive(a.getUUID());
			if (!ok && ticks % 40 == 0) log("waiting for Alice's lobby items: " + where(a) + ", " + LobbyKit.why(a));
			return ok;
		}, 200);
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
			try {
				Lobby.clear();
			} catch (java.io.IOException e) {
				check("the lobby can be cleared", false, e.toString());
			}
			check("nobody is left in a queue", Queue.size() == 0);
		});

		// ---------------------------------------------------------------- Builder rank and custom arenas
		step("builder: Alice is not a Builder", () -> {
			BuilderRank.resetForTest();
			check("Alice has no rank", !BuilderRank.hasRank(a.getUUID()) && !BuilderRank.can(a));
			run(a, "builder");
			check("/builder says how to get it", a.heard("You don't have the Builder rank"));
			run(a, "createarena flat");
			check("she can't open the studio", a.heard("Only Builders can make arenas") && !ArenaStudio.isIn(a.getUUID()));
			run(a, "createarena");
			check("the menu explains the rank", menuHas(a, "Builders only"));
			a.closeContainer();
			run(a, "builder give Alice");
			check("a normal player can't give the rank", !BuilderRank.hasRank(a.getUUID()));
		});
		step("builder: the rank lasts three weeks", () -> {
			long t0 = 1_800_000_000_000L;
			long day = 24L * 3600_000;
			BuilderRank.setClockForTest(t0);
			BuilderRank.grant(a.getUUID(), BuilderRank.DEFAULT_WEEKS);
			check("Alice is a Builder", BuilderRank.hasRank(a.getUUID()) && BuilderRank.can(a));
			check("21 days left", BuilderRank.timeLeft(a.getUUID()).equals("21 days left"), BuilderRank.timeLeft(a.getUUID()));
			BuilderRank.setClockForTest(t0 + 20 * day + day / 2);
			check("12 hours left on day 20 and a half", BuilderRank.timeLeft(a.getUUID()).equals("12 hours left"), BuilderRank.timeLeft(a.getUUID()));
			BuilderRank.setClockForTest(t0 + 21 * day + 1);
			check("after three weeks it is over", !BuilderRank.hasRank(a.getUUID()) && BuilderRank.timeLeft(a.getUUID()).equals("over"));
			BuilderRank.setClockForTest(t0);
			BuilderRank.grant(a.getUUID(), 1);
			check("a week is added to the rank that is running", BuilderRank.timeLeft(a.getUUID()).equals("28 days left"), BuilderRank.timeLeft(a.getUUID()));
			BuilderRank.revoke(a.getUUID());
			check("it can be taken away", !BuilderRank.hasRank(a.getUUID()));
			BuilderRank.resetForTest();
			BuilderRank.grant(a.getUUID(), BuilderRank.DEFAULT_WEEKS);
			check("with the real clock it works too", BuilderRank.hasRank(a.getUUID()));
			run(a, "builder");
			check("/builder tells her", a.heard("You are a Builder"));
		});

		step("studio: Bob is not a Builder, Alice starts from the flat template", () -> {
			run(b, "createarena flat");
			check("Bob is refused", !ArenaStudio.isIn(b.getUUID()) && b.heard("Only Builders can make arenas"));
			ArenaStore.deleteForTest(a.getUUID());
			run(a, "createarena flat");
			check("Alice is in the studio and busy", ArenaStudio.isIn(a.getUUID()) && DuelManager.isBusy(a.getUUID()));
			check("in the duel world, in creative", a.level().dimension().equals(Arena.DIMENSION) && a.gameMode() == GameType.CREATIVE, where(a));
			check("her things are backed up", PlayerBackup.exists(a.getUUID()));
			studioCx = ArenaStudio.plotOf(a.getUUID());
			ServerLevel lvl = Arena.level(server);
			check("grass floor, dirt and bedrock like the template", lvl.getBlockState(at(studioCx + 10, 100, 5)).is(Blocks.GRASS_BLOCK)
				&& lvl.getBlockState(at(studioCx - 31, 99, -32)).is(Blocks.DIRT) && lvl.getBlockState(at(studioCx, 97, 0)).is(Blocks.BEDROCK));
			check("the corners of the box are in the plot", lvl.getBlockState(at(studioCx + 31, 100, 31)).is(Blocks.GRASS_BLOCK)
				&& lvl.getBlockState(at(studioCx + 32, 100, 0)).isAir());
			check("air above the floor", lvl.getBlockState(at(studioCx, 101, 0)).isAir());
			run(a, "queue random");
			check("she can't queue while building", !Queue.isQueued(a.getUUID()));
		});
		step("studio: building only works inside the box", () -> {
			ServerLevel lvl = Arena.level(server);
			BlockHitResult inside = new BlockHitResult(new Vec3(studioCx + 5.5, 101, 0.5), Direction.UP, at(studioCx + 5, 100, 0), false);
			BlockHitResult outside = new BlockHitResult(new Vec3(studioCx + 40.5, 101, 0.5), Direction.UP, at(studioCx + 40, 100, 0), false);
			BlockHitResult edge = new BlockHitResult(new Vec3(studioCx + 31.9, 101, 0.5), Direction.EAST, at(studioCx + 31, 101, 0), false);
			var use = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT;
			check("placing inside is allowed", use.invoker().interact(a, lvl, InteractionHand.MAIN_HAND, inside) == net.minecraft.world.InteractionResult.PASS);
			check("placing outside is refused", use.invoker().interact(a, lvl, InteractionHand.MAIN_HAND, outside) == net.minecraft.world.InteractionResult.FAIL);
			check("placing on the edge, into the next block outside, is refused", use.invoker().interact(a, lvl, InteractionHand.MAIN_HAND, edge) == net.minecraft.world.InteractionResult.FAIL);
			var attack = net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT;
			check("breaking inside is allowed", attack.invoker().interact(a, lvl, InteractionHand.MAIN_HAND, at(studioCx + 5, 100, 0), Direction.UP) == net.minecraft.world.InteractionResult.PASS);
			check("breaking outside is refused", attack.invoker().interact(a, lvl, InteractionHand.MAIN_HAND, at(studioCx + 40, 100, 0), Direction.UP) == net.minecraft.world.InteractionResult.FAIL);
			check("the break event also says no outside", !net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker()
				.beforeBlockBreak(lvl, a, at(studioCx + 40, 100, 0), lvl.getBlockState(at(studioCx + 40, 100, 0)), null));
		});
		step("studio: spawn points", () -> {
			ServerLevel lvl = Arena.level(server);
			a.teleportTo(lvl, studioCx + 3.5, 101, 2.5, java.util.Set.of(), 90f, 0f, true);
			run(a, "setspawn 1");
			check("spawn 1 was set", a.heard("Spawn of player 1 set"));
			a.teleportTo(lvl, studioCx + 45.5, 101, 0.5, java.util.Set.of(), 0f, 0f, true);
			run(a, "setspawn 2");
			check("a spawn outside the box is refused", a.heard("Stand inside the building box"));
			a.teleportTo(lvl, studioCx + 12.5, 101, -3.5, java.util.Set.of(), -90f, 0f, true);
			run(a, "setspawn 2");
			check("spawn 2 was set", a.heard("Spawn of player 2 set"));
			lvl.setBlock(at(studioCx + 4, 101, 4), Blocks.OBSIDIAN.defaultBlockState(), 2);
			lvl.setBlock(at(studioCx - 20, 130, 20), Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
			a.teleportTo(lvl, studioCx + 60.5, 101, 0.5, java.util.Set.of(), 0f, 0f, true);
		}, () -> Math.abs(a.getX() - studioCx) < 40, 100);
		step("studio: somebody who walks away is brought back", () -> {
			check("Alice is inside the box area again", Math.abs(a.getX() - studioCx) < 40, where(a));
		});
		step("studio: saving the arena", () -> {
			run(a, "savearena draft");
			check("'draft' is reserved", a.heard("is the name of your draft"));
			run(a, "savearena Castle");
			check("saved as 'castle' (any case)", ArenaStore.exists(a.getUUID(), "castle") && a.heard("Saved the arena 'castle'"));
			check("the publish button was offered", a.heard("[PUBLISH]"));
			try {
				var data = ArenaStore.load(ArenaStore.key(a.getUUID(), "castle")).orElse(null);
				check("it can be read back", data != null);
				if (data != null) {
					check("the obsidian is in the file", data.blocks[ArenaData.index(36, 17, 36)].is(Blocks.OBSIDIAN));
					check("so is the high block", data.blocks[ArenaData.index(12, 46, 52)].is(Blocks.DIAMOND_BLOCK));
					check("and the grass", data.blocks[ArenaData.index(5, 16, 5)].is(Blocks.GRASS_BLOCK));
					check("the owner is Alice", a.getUUID().equals(data.owner) && data.ownerName.equals("Alice") && data.template.equals("flat"));
					check("both spawns are saved (relative to the center)", data.hasBothSpawns() && Math.abs(data.spawn0[0] - 3.5) < 0.01 && Math.abs(data.spawn0[2] - 2.5) < 0.01
						&& Math.abs(data.spawn1[0] - 12.5) < 0.01 && Math.abs(data.spawn1[2] + 3.5) < 0.01 && Math.abs(data.spawn1[3] + 90) < 0.01);
				}
			} catch (java.io.IOException e) {
				check("the arena can be read", false, e.toString());
			}
		});
		step("arena: list, publish and the shelf", () -> {
			run(a, "arena list");
			check("/arena list shows castle as private", a.heard("castle  (private)"));
			run(b, "arena publish castle");
			check("Bob has no such arena", b.heard("You have no arena named 'castle'"));
			run(a, "arena publish castle");
			var shared = ArenaStore.findPublic("castle");
			check("Alice published it", shared.isPresent() && a.getUUID().equals(shared.get().owner()) && a.heard("is public now"));
			run(b, "arena public");
			check("the shelf shows castle by Alice", b.heard("castle by Alice"));
			run(b, "arena unpublish castle");
			check("only the owner can take it off", b.heard("Only Alice can take it off") && ArenaStore.findPublic("castle").isPresent());
			run(a, "arena list");
			check("/arena list now says public", a.heard("castle  (public)"));
		});
		step("studio: /leave keeps a draft and cleans the plot", () -> {
			run(a, "leave");
			check("Alice left the studio", !ArenaStudio.isIn(a.getUUID()) && !DuelManager.isBusy(a.getUUID()));
			check("back in the overworld in survival", a.level().dimension().equals(server.overworld().dimension()) && a.gameMode() == GameType.SURVIVAL, where(a) + " " + a.gameMode());
			check("her backup is gone", !PlayerBackup.exists(a.getUUID()));
			check("a draft was kept", ArenaStore.exists(a.getUUID(), "draft") && a.heard("A draft of your build was kept"));
			ServerLevel lvl = Arena.level(server);
			check("the plot is empty again", lvl.getBlockState(at(studioCx + 10, 100, 5)).isAir() && lvl.getBlockState(at(studioCx + 4, 101, 4)).isAir()
				&& lvl.getBlockState(at(studioCx - 20, 130, 20)).isAir());
			run(a, "arena list");
			check("the draft is not listed as an arena", !a.heard("draft  (private)"));
		});
		step("studio: editing the saved arena", () -> {
			run(a, "arena edit castle");
			check("Alice is in the studio again", ArenaStudio.isIn(a.getUUID()));
			studioCx = ArenaStudio.plotOf(a.getUUID());
			ServerLevel lvl = Arena.level(server);
			check("the arena was pasted: grass, obsidian and the high block", lvl.getBlockState(at(studioCx + 10, 100, 5)).is(Blocks.GRASS_BLOCK)
				&& lvl.getBlockState(at(studioCx + 4, 101, 4)).is(Blocks.OBSIDIAN) && lvl.getBlockState(at(studioCx - 20, 130, 20)).is(Blocks.DIAMOND_BLOCK));
			lvl.setBlock(at(studioCx + 4, 101, 4), Blocks.AIR.defaultBlockState(), 2);
			run(a, "leave");
			check("left", !ArenaStudio.isIn(a.getUUID()));
			run(a, "arena edit nothere");
			check("an unknown arena is refused", a.heard("You have no arena named 'nothere'") && !ArenaStudio.isIn(a.getUUID()));
		});
		step("studio: the Create Arena menu for a Builder", () -> {
			run(a, "createarena");
			check("it offers the three templates", menuHas(a, "Void") && menuHas(a, "Flat grass") && menuHas(a, "Bedrock box"));
			check("and to continue the draft", menuHas(a, "Continue my draft"));
			check("and to edit an arena", menuHas(a, "Edit one of my arenas"));
			menuStep(a, "Edit one of my arenas");
		});
		step("studio: the list of her arenas", () -> {
			check("castle is listed", menuHas(a, "castle"));
			check("the draft is not", !menuHas(a, "draft"));
			a.closeContainer();
			run(a, "createarena void");
			studioCx = ArenaStudio.plotOf(a.getUUID());
			ServerLevel lvl = Arena.level(server);
			check("the void template is just a bedrock pad", lvl.getBlockState(at(studioCx, 100, 0)).is(Blocks.BEDROCK) && lvl.getBlockState(at(studioCx + 2, 100, -2)).is(Blocks.BEDROCK)
				&& lvl.getBlockState(at(studioCx + 3, 100, 0)).isAir() && lvl.getBlockState(at(studioCx + 10, 100, 5)).isAir());
			run(a, "leave");
			run(a, "createarena box");
			studioCx = ArenaStudio.plotOf(a.getUUID());
			lvl = Arena.level(server);
			check("the box template has a floor and walls", lvl.getBlockState(at(studioCx, 100, 0)).is(Blocks.BEDROCK) && lvl.getBlockState(at(studioCx - 32, 110, 0)).is(Blocks.BEDROCK)
				&& lvl.getBlockState(at(studioCx + 31, 124, 31)).is(Blocks.BEDROCK) && lvl.getBlockState(at(studioCx + 31, 125, 31)).isAir() && lvl.getBlockState(at(studioCx, 110, 0)).isAir());
			run(a, "savearena boxy");
			check("a template arena can be saved at once (it has default spawns)", ArenaStore.exists(a.getUUID(), "boxy"));
			run(a, "leave");
			run(a, "arena delete boxy");
			check("an arena can be deleted", !ArenaStore.exists(a.getUUID(), "boxy"));
			run(a, "arena delete draft");
			check("the draft can't be deleted like an arena", a.heard("You have no arena named 'draft'"));
		});

		step("custom arena: Alice picks it in the /duel menu", () -> {
			run(a, "duel Bob");
			menuStep(a, "qkit");
		});
		step("custom arena: menu, the arena step", () -> {
			check("the arena step offers custom arenas", menuHas(a, "Custom arena"));
			menuStep(a, "Custom arena");
		});
		step("custom arena: menu, the list", () -> {
			check("her castle is listed", menuHas(a, "castle"));
			menuStep(a, "castle");
		});
		step("custom arena: menu, how it is decided", () -> { menuStep(a, "First to N points"); });
		step("custom arena: menu, amount", () -> { menuStep(a, "First to 2"); });
		step("custom arena: menu, rules", () -> {
			check("building can be switched", menuHas(a, "Building: ON"));
			menuStep(a, "Continue");
		});
		step("custom arena: menu, send", () -> {
			check("the summary names the arena", menuHas(a, "Duel vs Bob"));
			menuStep(a, "Send the challenge");
			check("Bob got the request naming the arena", b.heard("Custom arena 'castle'"));
		});
		step("custom arena: Bob accepts", () -> {
			run(b, "accept Alice");
			check("a duel started", duel(a) != null && duel(b) == duel(a));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("custom arena: the duel is in her arena", () -> {
			Duel d = duel(a);
			studioCx = Arena.centerX(d.slot);
			ServerLevel lvl = Arena.level(server);
			check("it is a custom arena duel", d.config.arena() == ArenaKind.CUSTOM && d.config.customName().equals("castle"), d.config.toString());
			check("they are in the duel world", a.level().dimension().equals(Arena.DIMENSION) && b.level().dimension().equals(Arena.DIMENSION), where(a) + " / " + where(b));
			check("the arena was pasted: grass, the high block", lvl.getBlockState(at(studioCx + 10, 100, 5)).is(Blocks.GRASS_BLOCK)
				&& lvl.getBlockState(at(studioCx - 20, 130, 20)).is(Blocks.DIAMOND_BLOCK));
			check("Alice is at spawn 1 and Bob at spawn 2", Math.abs(a.getX() - (studioCx + 3.5)) < 1.5 && Math.abs(a.getZ() - 2.5) < 1.5
				&& Math.abs(b.getX() - (studioCx + 12.5)) < 1.5 && Math.abs(b.getZ() + 3.5) < 1.5, where(a) + " / " + where(b));
			BlockHitResult target = new BlockHitResult(new Vec3(studioCx + 0.5, 101, 0.5), Direction.UP, at(studioCx, 100, 0), false);
			useOn(a, new ItemStack(Items.STONE), target);
			check("building is on, so she can place a block", lvl.getBlockState(at(studioCx, 101, 0)).is(Blocks.STONE));
			lvl.setBlock(at(studioCx + 20, 120, 0), Blocks.TNT.defaultBlockState(), 2);
			hit(a, b);
			check("round 1 went to Alice", d.points[0] == 1 && d.phase == Duel.Phase.ROUND_END, d.points[0] + "-" + d.points[1] + " " + d.phase);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 400);
		step("custom arena: round 2 started on a clean arena", () -> {
			ServerLevel lvl = Arena.level(server);
			check("the stone she placed is gone", lvl.getBlockState(at(studioCx, 101, 0)).isAir());
			check("the block somebody left in the air is gone", lvl.getBlockState(at(studioCx + 20, 120, 0)).isAir());
			check("the arena itself is still there", lvl.getBlockState(at(studioCx + 10, 100, 5)).is(Blocks.GRASS_BLOCK));
			check("this is round 2", duel(a).round == 2);
			b.teleportTo(lvl, studioCx + 0.5, 60, 0.5, java.util.Set.of(), 0f, 0f, true);
		}, () -> phaseIs(a, Duel.Phase.ENDED), 300);
		step("custom arena: falling loses the round and the arena closes", () -> {
			Duel d = duel(a);
			check("Alice won 2-0", d != null && d.winner == 0 && d.points[0] == 2, d == null ? "no duel" : d.winner + " " + d.points[0] + "-" + d.points[1]);
			check("Alice was told that Bob fell", a.heard("Bob fell into the void"));
		}, () -> noDuel(a) && noDuel(b), 600);
		step("custom arena: nothing is left in the duel world", () -> {
			ServerLevel lvl = Arena.level(server);
			check("the arena is cleaned away", lvl.getBlockState(at(studioCx + 10, 100, 5)).isAir() && lvl.getBlockState(at(studioCx - 20, 130, 20)).isAir());
			check("both are back in the overworld", a.level().dimension().equals(server.overworld().dimension()) && b.level().dimension().equals(server.overworld().dimension()),
				where(a) + " / " + where(b));
		});
		step("custom arena: Bob sees Alice's public arena", () -> {
			run(b, "duel Alice");
			menuStep(b, "bqkit");
		});
		step("custom arena: Bob's menu", () -> {
			menuStep(b, "Custom arena");
		});
		step("custom arena: Bob's list", () -> {
			check("castle is there as a public arena", menuHas(b, "castle"));
			b.closeContainer();
			DuelManager.cancelInvitesOf(b.getUUID());
		});
		step("custom arena: deleting an arena", () -> {
			run(a, "arena delete castle");
			check("it is gone, also from the shelf", !ArenaStore.exists(a.getUUID(), "castle") && ArenaStore.findPublic("castle").isEmpty());
			DuelManager.sendInvite(a, b, a.getUUID(), DuelConfig.inCustomArena("qkit", ArenaStore.key(a.getUUID(), "castle"), WinMode.FIRST_TO, 1, DuelConfig.Rules.DEFAULT), false);
			run(b, "accept Alice");
			check("a challenge to a deleted arena does not start", duel(a) == null && b.heard("The custom arena 'castle' no longer exists"));
			DuelManager.cancelInvitesOf(a.getUUID());
			ArenaStore.deleteForTest(a.getUUID());
			BuilderRank.resetForTest();
		});

		// ---------------------------------------------------------------- the weekly building contest
		step("contest: it starts by itself with a category", () -> {
			BuilderRank.resetForTest();
			Contest.resetForTest();
			Contest.setClockForTest(CONTEST_T0);
			BuilderRank.setClockForTest(CONTEST_T0);
			check("no contest yet", Contest.phase() == Contest.Phase.NONE);
			Contest.advance(server);
			check("a contest is running", Contest.building() && Contest.number() == 1 && !Contest.category().isEmpty(), Contest.category());
			run(a, "contest");
			check("/contest tells Alice what to build", a.heard("Build \"" + Contest.category() + "\" on your own plot"));
			check("and that she has no build", a.heard("You have no build yet"));
			check("five days to build", Contest.timeLeft().equals("5 days left"), Contest.timeLeft());
		});
		step("contest: Alice (not a Builder) builds her entry", () -> {
			check("Alice is not a Builder", !BuilderRank.hasRank(a.getUUID()));
			run(a, "contest enter");
			check("she is on a contest plot", ArenaStudio.modeOf(a.getUUID()) == ArenaStudio.Mode.CONTEST && DuelManager.isBusy(a.getUUID()));
			studioCx = ArenaStudio.plotOf(a.getUUID());
			ServerLevel lvl = Arena.level(server);
			check("the flat template is there", lvl.getBlockState(at(studioCx + 10, 100, 5)).is(Blocks.GRASS_BLOCK) && lvl.getBlockState(at(studioCx + 10, 101, 5)).isAir());
			run(a, "savearena nope");
			check("the arena commands don't work here", a.heard("You are not in the arena studio"));
			run(a, "contest save");
			check("with nothing built it is refused", a.heard("Your build is too small") && !Contest.hasEntry(a.getUUID()));
			for (int i = 0; i < 25; i++) lvl.setBlock(at(studioCx + i, 101, 3), Blocks.STONE_BRICKS.defaultBlockState(), 2);
			run(a, "contest save");
			check("with 25 blocks it is saved", Contest.hasEntry(a.getUUID()) && a.heard("Your entry was saved (25 blocks built)"));
			run(a, "leave");
			check("she left and the plot is clean", !ArenaStudio.isIn(a.getUUID()) && lvl.getBlockState(at(studioCx, 101, 3)).isAir());
			check("her entry is kept", Contest.hasEntry(a.getUUID()) && Contest.loadEntry(a.getUUID()) != null && a.heard("Your build is your entry"));
			run(a, "contest");
			check("/contest says she handed in a build", a.heard("You handed in a build"));
		});
		step("contest: Bob builds, Carl builds nothing", () -> {
			ServerLevel lvl = Arena.level(server);
			run(b, "contest enter");
			int cx = ArenaStudio.plotOf(b.getUUID());
			for (int i = 0; i < 30; i++) lvl.setBlock(at(cx - i, 102, -4), Blocks.IRON_BLOCK.defaultBlockState(), 2);
			run(b, "leave");
			check("Bob's entry was kept when he left", Contest.hasEntry(b.getUUID()) && !ArenaStudio.isIn(b.getUUID()));
			run(carl, "contest enter");
			check("Carl is on a plot", ArenaStudio.modeOf(carl.getUUID()) == ArenaStudio.Mode.CONTEST);
			run(carl, "leave");
			check("a plot with nothing built is not an entry", !Contest.hasEntry(carl.getUUID()) && carl.heard("too small to be an entry"));
			check("two builds so far", Contest.entryCount() == 2, "" + Contest.entryCount());
			run(carl, "contest vote");
			check("nobody can vote yet", carl.heard("The voting is not open"));
		});
		step("contest: Dora is still building when the time is up", () -> {
			ServerLevel lvl = Arena.level(server);
			run(dora, "contest enter");
			int cx = ArenaStudio.plotOf(dora.getUUID());
			for (int i = 0; i < 22; i++) lvl.setBlock(at(cx + 5, 101 + i, 7), Blocks.GOLD_BLOCK.defaultBlockState(), 2);
			Contest.setClockForTest(CONTEST_T0 + 4 * CONTEST_DAY);
			BuilderRank.setClockForTest(CONTEST_T0 + 4 * CONTEST_DAY);
			Contest.advance(server);
			check("after four days she can still build", Contest.building() && ArenaStudio.isIn(dora.getUUID()));
			Contest.setClockForTest(CONTEST_T0 + 5 * CONTEST_DAY + 1000);
			BuilderRank.setClockForTest(CONTEST_T0 + 5 * CONTEST_DAY + 1000);
			Contest.advance(server);
			check("after five days the voting is open", Contest.voting());
			check("Dora was taken out of her plot", !ArenaStudio.isIn(dora.getUUID()) && dora.level().dimension().equals(server.overworld().dimension()), where(dora));
			check("and her tower is a build too: three in the gallery", Contest.hasEntry(dora.getUUID()) && Contest.gallery().size() == 3, "" + Contest.gallery().size());
			check("two days to vote", Contest.timeLeft().equals("2 days left"), Contest.timeLeft());
			check("everybody was told", carl.heard("time to vote"));
			run(a, "contest enter");
			check("nobody can build any more", a.heard("no building contest to build for") && !ArenaStudio.isIn(a.getUUID()));
		});
		step("contest: the gallery", () -> {
			int na = Contest.numberOf(a.getUUID());
			check("every build has a number from 1 to 3", na >= 1 && na <= 3 && Contest.numberOf(b.getUUID()) >= 1 && Contest.numberOf(dora.getUUID()) >= 1);
			check("Carl (no build) has none", Contest.numberOf(carl.getUUID()) == 0);
			run(carl, "contest vote");
			check("Carl sees the three builds without names", menuHas(carl, "Build #1") && menuHas(carl, "Build #2") && menuHas(carl, "Build #3") && !menuHas(carl, "Build #4")
				&& !menuHas(carl, "Alice") && !menuHas(carl, "Bob"));
			run(a, "contest vote");
			check("Alice sees which one is hers", menuHas(a, "Build #" + na + " (yours)"));
			a.closeContainer();
			check("Carl clicks Alice's build", clickMenu(carl, "Build #" + na));
		});
		step("contest: Carl looks at Alice's build", () -> {
			int na = Contest.numberOf(a.getUUID());
			ServerLevel lvl = Arena.level(server);
			check("Carl is looking at it", ArenaStudio.modeOf(carl.getUUID()) == ArenaStudio.Mode.VIEW && a.getUUID().equals(ArenaStudio.viewing(carl.getUUID())));
			int cx = ArenaStudio.plotOf(carl.getUUID());
			check("her stone bricks are in his plot", lvl.getBlockState(at(cx + 10, 101, 3)).is(Blocks.STONE_BRICKS) && lvl.getBlockState(at(cx + 30, 101, 3)).isAir());
			check("in the duel world, in creative to fly around", carl.level().dimension().equals(Arena.DIMENSION) && carl.gameMode() == GameType.CREATIVE, where(carl));
			check("the vote button is there", carl.heard("[VOTE FOR #" + na + "]"));
			BlockHitResult hit = new BlockHitResult(new Vec3(cx + 5.5, 101, 0.5), Direction.UP, at(cx + 5, 100, 0), false);
			check("he can't place anything", net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(carl, lvl, InteractionHand.MAIN_HAND, hit) == net.minecraft.world.InteractionResult.FAIL);
			check("he can't break anything", net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.invoker()
				.interact(carl, lvl, InteractionHand.MAIN_HAND, at(cx + 10, 101, 3), Direction.UP) == net.minecraft.world.InteractionResult.FAIL
				&& !net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(lvl, carl, at(cx + 10, 101, 3), lvl.getBlockState(at(cx + 10, 101, 3)), null));
			run(carl, "contest enter");
			check("he can't start building while looking", !ArenaStudio.modeOf(carl.getUUID()).equals(ArenaStudio.Mode.CONTEST));
			int nb = Contest.numberOf(b.getUUID());
			run(carl, "contest view " + nb);
			check("he moved on to Bob's build", b.getUUID().equals(ArenaStudio.viewing(carl.getUUID())) && lvl.getBlockState(at(cx - 10, 102, -4)).is(Blocks.IRON_BLOCK)
				&& lvl.getBlockState(at(cx + 10, 101, 3)).isAir());
			run(carl, "contest view " + na);
		});
		step("contest: voting", () -> {
			int na = Contest.numberOf(a.getUUID());
			int nb = Contest.numberOf(b.getUUID());
			int nd = Contest.numberOf(dora.getUUID());
			run(carl, "contest vote " + na);
			check("Carl votes for Alice", a.getUUID().equals(Contest.votedFor(carl.getUUID())) && carl.heard("Your vote goes to build #" + na));
			run(a, "contest vote " + na);
			check("you can't vote for your own build", a.heard("You can't vote for your own build") && Contest.votedFor(a.getUUID()) == null);
			run(a, "contest vote 9");
			check("a build that doesn't exist", a.heard("There is no build number 9"));
			run(b, "contest vote " + na);
			run(dora, "contest vote " + nb);
			run(dora, "contest vote " + na);
			check("Dora changed her vote", dora.heard("you changed your vote") && a.getUUID().equals(Contest.votedFor(dora.getUUID())));
			run(a, "contest view " + nb);
			check("Alice looks at Bob's build", b.getUUID().equals(ArenaStudio.viewing(a.getUUID())) && a.heard("[VOTE FOR #" + nb + "]"));
			run(a, "contest vote " + nb);
			check("and votes for it", b.getUUID().equals(Contest.votedFor(a.getUUID())));
			check("Alice has 3 votes, Bob 1, Dora's tower 0", Contest.votesFor(a.getUUID()) == 3 && Contest.votesFor(b.getUUID()) == 1 && Contest.votesFor(dora.getUUID()) == 0
				&& Contest.voterCount() == 4, Contest.votesFor(a.getUUID()) + "/" + Contest.votesFor(b.getUUID()) + "/" + Contest.votesFor(dora.getUUID()) + " " + nd);
			run(carl, "contest");
			check("/contest shows Carl his vote", carl.heard("You voted for build #" + na));
			run(a, "leave");
			check("Alice left the gallery", !ArenaStudio.isIn(a.getUUID()) && a.heard("You left the gallery"));
		});
		step("contest: the voting ends and the winners are Builders", () -> {
			check("Carl is still looking at a build", ArenaStudio.modeOf(carl.getUUID()) == ArenaStudio.Mode.VIEW);
			Contest.setClockForTest(CONTEST_T0 + 7 * CONTEST_DAY + 2000);
			BuilderRank.setClockForTest(CONTEST_T0 + 7 * CONTEST_DAY + 2000);
			Contest.advance(server);
			check("the contest is over and the two-week break starts", Contest.phase() == Contest.Phase.REST && Contest.number() == 1 && Contest.timeLeft().equals("14 days left"),
				Contest.phase() + " " + Contest.number() + " " + Contest.timeLeft());
			check("nobody can build during the break", !Contest.building() && !Contest.voting());
			check("everybody was told about the break", carl.heard("The next building contest starts in 14 days."));
			check("Carl was taken out of the gallery", !ArenaStudio.isIn(carl.getUUID()));
			check("Alice is a Builder", BuilderRank.hasRank(a.getUUID()) && BuilderRank.timeLeft(a.getUUID()).equals("21 days left"), BuilderRank.timeLeft(a.getUUID()));
			check("so is Bob", BuilderRank.hasRank(b.getUUID()));
			check("Dora (no votes) and Carl (no build) are not", !BuilderRank.hasRank(dora.getUUID()) && !BuilderRank.hasRank(carl.getUUID()));
			check("the results were announced", carl.heard("1st: Alice - 3 votes") && carl.heard("2nd: Bob - 1 vote") && !carl.heard("3rd: Dora"));
			check("the winners were told", a.heard("You placed 1st in the building contest") && b.heard("You placed 2nd in the building contest"));
			check("the last contest is remembered", a.getUUID().equals(Contest.lastWinner(1)) && b.getUUID().equals(Contest.lastWinner(2)) && Contest.lastWinner(3) == null);
			check("the builds were thrown away", Contest.entryCount() == 0 && !Contest.hasEntry(a.getUUID()) && Contest.loadEntry(a.getUUID()) == null);
			run(dora, "contest results");
			check("/contest results prints them", dora.heard("They are Builders for 3 weeks"));
			run(dora, "contest");
			check("/contest says when the next one starts", dora.heard("There is no building contest right now. The next one starts in 14 days."));
			run(dora, "contest enter");
			check("nobody can enter during the break", dora.heard("no building contest to build for") && !ArenaStudio.isIn(dora.getUUID()));
		});
		step("contest: the winner can make arenas now", () -> {
			run(a, "createarena flat");
			check("Alice is in the arena studio", ArenaStudio.modeOf(a.getUUID()) == ArenaStudio.Mode.STUDIO);
			run(a, "leave");
			check("and left", !ArenaStudio.isIn(a.getUUID()));
			ArenaStore.deleteForTest(a.getUUID());
			run(carl, "createarena flat");
			check("Carl still can't", carl.heard("Only Builders can make arenas") && !ArenaStudio.isIn(carl.getUUID()));
		});
		step("contest: after the break a new contest starts", () -> {
			long t = CONTEST_T0 + 7 * CONTEST_DAY + 2000 + Contest.REST_DAYS * CONTEST_DAY - 1000;
			Contest.setClockForTest(t);
			BuilderRank.setClockForTest(t);
			Contest.advance(server);
			check("a second before the break ends there is still no contest", Contest.phase() == Contest.Phase.REST, Contest.phase() + "");
			Contest.setClockForTest(t + 2000);
			BuilderRank.setClockForTest(t + 2000);
			Contest.advance(server);
			check("two weeks after the results the contest #2 starts", Contest.building() && Contest.number() == 2, Contest.phase() + " " + Contest.number());
			check("it was announced", carl.heard("Building contest #2"));
		});
		step("contest: with one build only the contest is cancelled", () -> {
			ServerLevel lvl = Arena.level(server);
			run(a, "contest enter");
			int cx = ArenaStudio.plotOf(a.getUUID());
			for (int i = 0; i < 21; i++) lvl.setBlock(at(cx + i, 101, 9), Blocks.OAK_PLANKS.defaultBlockState(), 2);
			run(a, "leave");
			check("one build", Contest.entryCount() == 1);
			long t = CONTEST_T0 + 27 * CONTEST_DAY;
			Contest.setClockForTest(t);
			BuilderRank.setClockForTest(t);
			Contest.advance(server);
			check("the contest was cancelled and a new one (#3) started", Contest.building() && Contest.number() == 3, Contest.phase() + " " + Contest.number());
			check("everybody was told", carl.heard("was cancelled: not enough builds"));
			check("nobody won", Contest.lastWinner(1) == null && Contest.entryCount() == 0);
			run(carl, "contest end");
			check("a normal player can't end a contest", Contest.building() && Contest.number() == 3);
		});
		step("contest: an admin's category, and a contest where nobody votes", () -> {
			ServerLevel lvl = Arena.level(server);
			Contest.startNew(server, "giant pizza");
			check("the admin's category is used", Contest.category().equals("giant pizza") && Contest.number() == 4, Contest.category() + " " + Contest.number());
			for (Dummy who : new Dummy[] {a, b}) {
				run(who, "contest enter");
				int cx = ArenaStudio.plotOf(who.getUUID());
				for (int i = 0; i < 20; i++) lvl.setBlock(at(cx + i, 101, 11), Blocks.CLAY.defaultBlockState(), 2);
				run(who, "leave");
			}
			check("two builds", Contest.entryCount() == 2);
			String said = Contest.skip(server);
			check("skipping the building time opens the voting", Contest.voting() && said.contains("building time"), said);
			said = Contest.skip(server);
			check("skipping the voting ends the contest and starts the break", Contest.phase() == Contest.Phase.REST && Contest.number() == 4 && said.contains("voting"), said + " " + Contest.phase());
			check("with no votes nobody wins", Contest.lastWinner(1) == null && a.heard("Nobody voted"));
			said = Contest.skip(server);
			check("an admin can end the break early", Contest.building() && Contest.number() == 5 && said.contains("new contest"), said + " " + Contest.phase());
		});
		step("contest: clean up", () -> {
			Contest.resetForTest();
			BuilderRank.resetForTest();
			ArenaStore.deleteForTest(a.getUUID());
			ArenaStore.deleteForTest(b.getUUID());
			check("nobody is left on a plot", !ArenaStudio.isIn(a.getUUID()) && !ArenaStudio.isIn(b.getUUID()) && !ArenaStudio.isIn(carl.getUUID()) && !ArenaStudio.isIn(dora.getUUID()));
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

		// ---------------------------------------------------------------- parties and 2v2
		final int[] team2v2Before = new int[3];
		step("party: nobody is in a party at first", () -> {
			Party.resetForTest();
			check("all four are free", !DuelManager.isBusy(a.getUUID()) && !DuelManager.isBusy(b.getUUID()) && !DuelManager.isBusy(carl.getUUID()) && !DuelManager.isBusy(dora.getUUID()));
			check("nobody is in a party", !Party.inParty(a.getUUID()) && !Party.isFull(a.getUUID()) && Party.partnerOf(a.getUUID()) == null);
			run(a, "party");
			check("/party says so", a.heard("You are not in a party. /party invite"));
			run(a, "party accept");
			check("there is nothing to accept", a.heard("(or it expired)"));
			run(a, "party invite Alice");
			check("you can't be your own partner", a.heard("You can't be your own partner."));
			run(a, "party leave");
			check("you can't leave what you are not in", a.heard("You are not in a party.") && !Party.inParty(a.getUUID()));
		});
		step("party: an invitation can be denied", () -> {
			run(a, "party invite Bob");
			check("Alice is told it was sent", a.heard("Party invitation sent to Bob"));
			check("Bob gets the invitation with buttons", b.heard("wants you as a partner for 2v2 duels") && b.heard("[ACCEPT]") && b.heard("[DENY]"));
			run(b, "party deny");
			check("Bob denied", b.heard("You denied the invitation of Alice") && a.heard("Bob denied your party invitation"));
			check("nobody is in a party", !Party.inParty(a.getUUID()) && !Party.inParty(b.getUUID()));
			run(b, "party accept");
			check("a denied invitation is gone", !Party.inParty(b.getUUID()));
		});
		step("party: an invitation can be accepted", () -> {
			Queue.clearAll();
			run(b, "queue random");
			check("Bob waits in the random queue", Queue.isQueued(b.getUUID()));
			run(a, "party invite Bob");
			run(b, "party accept");
			check("joining a party takes you out of the queue", !Queue.isQueued(b.getUUID()));
			check("they are partners", Party.isFull(a.getUUID()) && Party.isFull(b.getUUID()) && Party.partnerOf(a.getUUID()).equals(b.getUUID())
				&& Party.partnerOf(b.getUUID()).equals(a.getUUID()));
			check("Alice is the leader", Party.isLeader(a.getUUID()) && !Party.isLeader(b.getUUID()));
			check("both were told", b.heard("You are in a party with Alice") && a.heard("Bob is your partner now"));
			run(a, "party");
			check("/party shows the partner", a.heard("Your party: you and Bob"));
		});
		step("party: a third player can't join", () -> {
			run(carl, "party invite Alice");
			check("Carl can't invite somebody who has a party", carl.heard("Alice is in a party already."));
			run(a, "party invite Carl");
			check("Alice's party is full", a.heard("Your party is full already."));
			check("Carl got nothing to accept", !carl.heard("wants you as a partner"));
			run(a, "queue random");
			check("a party can't use the 1v1 queue", a.heard("The queue is for 1v1.") && !Queue.isQueued(a.getUUID()));
		});
		step("party: an invitation runs out after a minute", () -> {
			long t0 = 5_000_000L;
			Party.setClockForTest(t0);
			run(carl, "party invite Dora");
			check("Dora is invited", dora.heard("Carl") && dora.heard("wants you as a partner"));
			Party.setClockForTest(t0 + 61_000);
			run(dora, "party accept");
			check("too late: no party", !Party.inParty(dora.getUUID()) && !Party.inParty(carl.getUUID()));
			Party.setClockForTest(t0 + 70_000);
			run(carl, "party invite Dora");
			Party.setClockForTest(t0 + 80_000);
			run(dora, "party accept");
			check("in time: Carl and Dora are partners", Party.isFull(carl.getUUID()) && Party.partnerOf(carl.getUUID()).equals(dora.getUUID()));
			Party.setClockForTest(0);
		});
		step("party: a party only duels another party", () -> {
			run(dora, "party leave");
			check("leaving ends the party for both", !Party.inParty(dora.getUUID()) && !Party.inParty(carl.getUUID()) && carl.heard("Dora left the party, so it is over."));
			DuelManager.sendInvite(carl, a, carl.getUUID(), new DuelConfig("qkit", false, WinMode.FIRST_TO, 2), false);
			check("a single player can't challenge a party", carl.heard("Alice is in a party, so they only play 2v2."));
			DuelManager.sendInvite(a, carl, a.getUUID(), new DuelConfig("qkit", false, WinMode.FIRST_TO, 2), false);
			check("a party can't challenge a single player", a.heard("You are in a party, so you can only duel another party (2v2). Carl is not in one."));
			DuelManager.sendInvite(a, b, a.getUUID(), new DuelConfig("qkit", false, WinMode.FIRST_TO, 2), false);
			check("a player can't challenge their own partner", a.heard("That is your own partner!"));
			run(carl, "accept Alice");
			run(a, "accept Carl");
			run(b, "accept Alice");
			check("none of those left an invitation to accept", !DuelManager.isInDuel(a.getUUID()) && !DuelManager.isInDuel(carl.getUUID()) && !DuelManager.isInDuel(b.getUUID()));
			run(carl, "party invite Dora");
			run(dora, "party accept");
			check("Carl and Dora are a party again", Party.isFull(carl.getUUID()) && Party.isFull(dora.getUUID()));
		});
		step("2v2: Alice challenges Carl, who accepts", () -> {
			Victory.resetForTest();
			for (Dummy p : new Dummy[] {a, b, carl, dora}) p.getInventory().clearContent();
			team2v2Before[0] = StatsStore.get(a.getUUID()).elo;
			team2v2Before[1] = StatsStore.get(carl.getUUID()).elo;
			team2v2Before[2] = StatsStore.get(a.getUUID()).duels;
			a.getInventory().setItem(22, new ItemStack(Items.COBBLESTONE, 7));
			b.getInventory().setItem(22, new ItemStack(Items.COBBLESTONE, 6));
			carl.getInventory().setItem(22, new ItemStack(Items.COBBLESTONE, 5));
			dora.getInventory().setItem(22, new ItemStack(Items.COBBLESTONE, 4));
			DuelManager.sendInvite(a, carl, a.getUUID(), new DuelConfig("qkit", false, WinMode.FIRST_TO, 2), false);
			check("Carl is told it is a 2v2", carl.heard("and their partner challenged you and your partner to a 2v2 duel!"));
			run(carl, "accept Alice");
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			check("a 2v2 exists for the four", t != null && DuelManager.teamDuelOf(b.getUUID()) == t && DuelManager.teamDuelOf(carl.getUUID()) == t && DuelManager.teamDuelOf(dora.getUUID()) == t);
			check("it is not a 1v1", duel(a) == null && duel(carl) == null);
			check("team 0 is Alice and Bob, team 1 is Carl and Dora", t != null && t.teamName(0).equals("Alice & Bob") && t.teamName(1).equals("Carl & Dora"));
			check("they are all in a duel", DuelManager.isInDuel(a.getUUID()) && DuelManager.isInDuel(b.getUUID()) && DuelManager.isInDuel(carl.getUUID()) && DuelManager.isInDuel(dora.getUUID()));
			check("they were told the rules", a.heard("2v2: Alice & Bob vs Carl & Dora") && dora.heard("No friendly fire."));
			check("the party can't end while it plays", Party.leave(a) != null);
		}, () -> {
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			return t != null && t.phase == Duel.Phase.FIGHTING;
		}, 600);
		step("2v2: the four are in the arena with the kit", () -> {
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			check("all four are in the arena world", a.level().dimension().equals(t.space.dimension()) && b.level().dimension().equals(t.space.dimension())
				&& carl.level().dimension().equals(t.space.dimension()) && dora.level().dimension().equals(t.space.dimension()), where(a) + " / " + where(carl));
			check("teammates start next to each other", a.distanceTo(b) > 1.5 && a.distanceTo(b) < 4 && carl.distanceTo(dora) > 1.5 && carl.distanceTo(dora) < 4,
				a.distanceTo(b) + " / " + carl.distanceTo(dora));
			check("the teams start far from each other", a.distanceTo(carl) > 6 && b.distanceTo(dora) > 6, "" + a.distanceTo(carl));
			check("everybody got the sword of the kit", a.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && b.getInventory().getItem(0).is(Items.DIAMOND_SWORD)
				&& carl.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && dora.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
			check("the cobblestone of their own inventory is not there", a.getInventory().getItem(22).isEmpty() && dora.getInventory().getItem(22).isEmpty());
			check("score 0 - 0 in round 1", t.points[0] == 0 && t.points[1] == 0 && t.round == 1);
			check("Alice's sidebar", sidebarHas(a, "2v2 with Bob") && sidebarHas(a, "vs Carl & Dora") && sidebarHas(a, "Alive: 2 vs 2"), String.valueOf(sidebar(a)));
			check("Carl's sidebar", sidebarHas(carl, "2v2 with Dora") && sidebarHas(carl, "vs Alice & Bob"), String.valueOf(sidebar(carl)));
			DuelManager.listDuels(b);
			check("/duels lists the 2v2", b.heard("2v2: Alice & Bob 0 - 0 Carl & Dora"));
			check("busy players can't be challenged", DuelManager.busyReason(a) != null && DuelManager.busyReason(dora) != null);
		});
		step("2v2: no friendly fire", () -> {
			hit(a, b);
			hit(carl, dora);
			check("Bob and Dora are fine", b.getHealth() == b.getMaxHealth() && dora.getHealth() == dora.getMaxHealth()
				&& b.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SURVIVAL && dora.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SURVIVAL);
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			check("nobody is out", t.phase == Duel.Phase.FIGHTING && t.points[0] == 0 && t.points[1] == 0);
		});
		step("2v2: a partner can't end the party in the middle of a duel", () -> {
			run(b, "party leave");
			check("a partner can't leave in the middle of a duel", b.heard("You are in a duel: finish it or use /leave first.") && Party.inParty(b.getUUID()));
		});
		step("2v2: a totem in hand saves Carl from the knockout", () -> {
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			carl.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			hit(a, carl);
			check("Carl popped the totem and is still in the fight", carl.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SURVIVAL
				&& carl.getMainHandItem().isEmpty() && carl.getHealth() > 0f && carl.getHealth() <= 1.01f, carl.getMainHandItem() + " " + carl.getHealth());
			check("and the round goes on with four alive", t.phase == Duel.Phase.FIGHTING && t.points[0] == 0 && t.points[1] == 0 && sidebarHas(a, "Alive: 2 vs 2"), String.valueOf(sidebar(a)));
			carl.setHealth(carl.getMaxHealth());
			carl.removeAllEffects();
		});
		step("2v2: Alice knocks Carl out; the round goes on", () -> {
			hit(a, carl);
			check("Carl is a spectator now", carl.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SPECTATOR);
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			check("the round is still on", t.phase == Duel.Phase.FIGHTING && t.points[0] == 0 && t.points[1] == 0);
			check("they were told who did it", a.heard("Alice defeated Carl.") && dora.heard("Alice defeated Carl."));
			hit(b, carl);
			check("a player who is out can't be hit again or lose a round twice", t.phase == Duel.Phase.FIGHTING && t.points[0] == 0);
		}, () -> sidebarHas(a, "Alive: 2 vs 1"), 200);
		step("2v2: Bob knocks Dora out; Alice and Bob win round 1", () -> {
			hit(b, dora);
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			check("round 1 goes to team 0", t.points[0] == 1 && t.points[1] == 0 && t.phase == Duel.Phase.ROUND_END && t.winner == -2);
			check("everybody was told", carl.heard("Round 1 goes to Alice & Bob.") && b.heard("Round 1 goes to Alice & Bob.  Score: 1 - 0"));
			check("Carl's score line is the other way round", dora.heard("Score: 0 - 1"));
			check("no clutch: nobody of the winners was out", Achievements.counter(a.getUUID(), "team_clutches") == 0 && Achievements.counter(b.getUUID(), "team_clutches") == 0);
		}, () -> {
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			return t != null && t.round == 2 && t.phase == Duel.Phase.FIGHTING;
		}, 800);
		step("2v2: round 2 starts with everybody back in the fight", () -> {
			check("Carl is not a spectator any more", carl.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SURVIVAL && dora.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SURVIVAL);
			check("all have full health and the kit again", a.getHealth() == a.getMaxHealth() && carl.getHealth() == carl.getMaxHealth()
				&& carl.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && dora.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
			check("teams are apart again", a.distanceTo(carl) > 6, "" + a.distanceTo(carl));
			hit(carl, a);
			check("Alice is out", a.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SPECTATOR);
			hit(dora, b);
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			check("round 2 goes to Carl and Dora: 1 - 1", t.points[0] == 1 && t.points[1] == 1 && t.phase == Duel.Phase.ROUND_END, t.points[0] + "-" + t.points[1] + " " + t.phase);
			check("they were told", a.heard("Round 2 goes to Carl & Dora."));
			check("Carl and Dora clutched nothing: both were alive", Achievements.counter(carl.getUUID(), "team_clutches") == 0);
		}, () -> {
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			return t != null && t.round == 3 && t.phase == Duel.Phase.FIGHTING;
		}, 800);
		step("2v2: round 3, Alice wins it alone", () -> {
			hit(carl, b);
			check("Bob is out", b.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SPECTATOR);
			hit(a, carl);
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			check("one rival to go", t.phase == Duel.Phase.FIGHTING);
			hit(a, dora);
			check("the match is decided: team 0 wins 2 - 1", t.points[0] == 2 && t.points[1] == 1 && t.winner == 0, t.points[0] + "-" + t.points[1] + " winner " + t.winner);
			check("Alice clutched, Bob did not", Achievements.counter(a.getUUID(), "team_clutches") == 1 && Achievements.counter(b.getUUID(), "team_clutches") == 0,
				Achievements.counter(a.getUUID(), "team_clutches") + " / " + Achievements.counter(b.getUUID(), "team_clutches"));
		}, () -> {
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			return t != null && t.phase == Duel.Phase.ENDED && sidebarHas(a, "You won!") && sidebarHas(dora, "You lost");
		}, 800);
		step("2v2: the end of the match", () -> {
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			check("the final score was told", carl.heard("2v2 finished: Alice & Bob 2 - 1 Carl & Dora"));
			check("the winners have the win, the losers do not", Achievements.counter(a.getUUID(), "team_wins") == 1 && Achievements.counter(b.getUUID(), "team_wins") == 1
				&& Achievements.counter(carl.getUUID(), "team_wins") == 0 && Achievements.counter(dora.getUUID(), "team_wins") == 0);
			check("Better Together and Clutch", Achievements.has(a.getUUID(), "team_first") && Achievements.has(b.getUUID(), "team_first") && Achievements.has(a.getUUID(), "clutch_1")
				&& !Achievements.has(b.getUUID(), "clutch_1") && !Achievements.has(carl.getUUID(), "team_first"));
			check("a 2v2 does not touch the Elo or the 1v1 numbers", StatsStore.get(a.getUUID()).elo == team2v2Before[0] && StatsStore.get(carl.getUUID()).elo == team2v2Before[1]
				&& StatsStore.get(a.getUUID()).duels == team2v2Before[2], StatsStore.get(a.getUUID()).elo + " / " + StatsStore.get(carl.getUUID()).elo + " / " + StatsStore.get(a.getUUID()).duels);
			check("the sidebar says who won", sidebarHas(a, "You won!") && sidebarHas(dora, "You lost"), String.valueOf(sidebar(a)));
			check("the two winners' fireworks went up and the losers' did not", Victory.fireworkLaunches() == 2 && Victory.showsStarted() == 2, Victory.fireworkLaunches() + " / " + Victory.showsStarted());
			run(carl, "leave");
			check("Carl got himself back", !DuelManager.isInDuel(carl.getUUID()) && carl.getInventory().getItem(22).is(Items.COBBLESTONE) && carl.getInventory().getItem(22).getCount() == 5);
			check("the others are still there until it closes", DuelManager.isInDuel(a.getUUID()) && t.phase == Duel.Phase.ENDED);
			run(carl, "spectate Alice");
			check("a 2v2 can't be watched yet", carl.heard("2v2 duels can't be watched yet.") && !Spectators.isIn(carl.getUUID()));
		}, () -> !DuelManager.isInDuel(a.getUUID()) && !DuelManager.isInDuel(b.getUUID()) && !DuelManager.isInDuel(dora.getUUID()), 800);
		step("2v2: everybody got their things back", () -> {
			check("Alice, Bob and Dora have their cobblestone again", a.getInventory().getItem(22).getCount() == 7 && b.getInventory().getItem(22).getCount() == 6
				&& dora.getInventory().getItem(22).getCount() == 4);
			check("and the kit is gone", a.getInventory().getItem(0).isEmpty() && b.getInventory().getItem(0).isEmpty() && dora.getInventory().getItem(0).isEmpty());
			check("all in survival again", a.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SURVIVAL && b.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SURVIVAL);
			check("no duel is left", DuelManager.activeDuels() == 0 && DuelManager.teamDuelOf(a.getUUID()) == null);
			check("the parties are still there", Party.isFull(a.getUUID()) && Party.isFull(carl.getUUID()));
		});
		step("2v2: a player who leaves makes their team forfeit", () -> {
			invite(a, carl, new DuelConfig("qkit", false, WinMode.FIRST_TO, 3));
			check("a new 2v2 started", DuelManager.teamDuelOf(a.getUUID()) != null && DuelManager.teamDuelOf(dora.getUUID()) != null);
		}, () -> {
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			return t != null && t.phase == Duel.Phase.FIGHTING;
		}, 600);
		step("2v2: Dora types /leave", () -> {
			run(dora, "leave");
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			check("the duel is over", t != null && t.phase == Duel.Phase.ENDED && t.winner == 0);
			check("Dora is out and the others were told", !DuelManager.isInDuel(dora.getUUID()) && a.heard("Dora left the duel, so Carl & Dora forfeit."));
			check("Alice and Bob won it", Achievements.counter(a.getUUID(), "team_wins") == 2 && Achievements.counter(b.getUUID(), "team_wins") == 2);
			check("Carl lost it", Achievements.counter(carl.getUUID(), "team_wins") == 0);
			run(carl, "leave");
			check("Carl can leave too", !DuelManager.isInDuel(carl.getUUID()));
		}, () -> !DuelManager.isInDuel(a.getUUID()) && !DuelManager.isInDuel(b.getUUID()), 800);
		step("2v2: a draw offer", () -> {
			for (Dummy p : new Dummy[] {a, b, carl, dora}) p.inbox.clear();
			invite(a, carl, new DuelConfig("qkit", false, WinMode.FIRST_TO, 3));
			check("a new 2v2 started", DuelManager.teamDuelOf(a.getUUID()) != null);
		}, () -> {
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			return t != null && t.phase == Duel.Phase.FIGHTING;
		}, 600);
		step("2v2: Carl's team accepts Alice's team's draw", () -> {
			TeamDuel t = DuelManager.teamDuelOf(a.getUUID());
			run(a, "draw");
			check("both of the other team got the offer", carl.heard("Alice's team offers a draw.") && dora.heard("Alice's team offers a draw.") && Draws.hasOffer(t));
			check("Alice's own partner did not get it", !b.heard("offers a draw"));
			run(b, "draw accept");
			check("her partner can't accept it for the rival", b.heard("There is no draw offer to accept") && t.phase == Duel.Phase.FIGHTING);
			run(dora, "draw accept");
			check("Dora accepts: the 2v2 ends in a tie", t.winner == -1 && t.phase == Duel.Phase.ENDED && a.heard("Draw agreed") && carl.heard("Draw agreed"), t.winner + " " + t.phase);
		}, () -> !DuelManager.isInDuel(a.getUUID()) && !DuelManager.isInDuel(b.getUUID()) && !DuelManager.isInDuel(dora.getUUID()) && !DuelManager.isInDuel(carl.getUUID()), 800);
		step("2v2: the parties can be ended", () -> {
			check("the arena is free", DuelManager.activeDuels() == 0);
			run(a, "party leave");
			check("Alice left, so the party is over for both", !Party.inParty(a.getUUID()) && !Party.inParty(b.getUUID()) && b.heard("Alice left the party, so it is over."));
			Party.resetForTest();
			check("everything is reset", !Party.inParty(carl.getUUID()) && !Party.inParty(dora.getUUID()));
			for (Dummy p : new Dummy[] {a, b, carl, dora}) p.getInventory().clearContent();
		});

		// ---------------------------------------------------------------- achievements and titles
		step("achievements: the duels, kits and builds of this whole test already counted", () -> {
			check("Alice has First Steps and First Victory", Achievements.has(a.getUUID(), "first_duel") && Achievements.has(a.getUUID(), "first_win"));
			check("her win counter is her real number of wins", Achievements.counter(a.getUUID(), "wins") == StatsStore.get(a.getUUID()).wins && StatsStore.get(a.getUUID()).wins > 0,
				Achievements.counter(a.getUUID(), "wins") + " vs " + StatsStore.get(a.getUUID()).wins);
			check("Bob played too", Achievements.has(b.getUUID(), "first_duel"));
			check("Alice won 2-0 in her own arena: Flawless", Achievements.has(a.getUUID(), "flawless"));
			check("she has dueled in at least two kinds of arena", Achievements.counter(a.getUUID(), "arena_kinds") >= 2, "" + Achievements.counter(a.getUUID(), "arena_kinds"));
			check("she saved a kit", Achievements.has(a.getUUID(), "kit_first"));
			check("she saved and shared an arena", Achievements.has(a.getUUID(), "arena_first") && Achievements.has(a.getUUID(), "arena_share"));
			check("she won the building contest", Achievements.has(a.getUUID(), "contest_join") && Achievements.has(a.getUUID(), "contest_podium") && Achievements.has(a.getUUID(), "contest_win"));
			check("Bob was second: podium but not the win", Achievements.has(b.getUUID(), "contest_podium") && !Achievements.has(b.getUUID(), "contest_win"));
			check("Carl voted once", Achievements.counter(carl.getUUID(), "contest_votes") == 1, "" + Achievements.counter(carl.getUUID(), "contest_votes"));
		});
		step("achievements: counters unlock achievements", () -> {
			Achievements.resetForTest();
			java.util.UUID id = carl.getUUID();
			check("nothing is unlocked", Achievements.unlockedCount(id) == 0);
			Achievements.max(id, "Carl", "wins", 1);
			check("1 win: First Victory, but not Duelist", Achievements.has(id, "first_win") && !Achievements.has(id, "wins_10"));
			Achievements.add(id, "Carl", "bot_kos", 6);
			Achievements.add(id, "Carl", "bot_kos", 4);
			check("6 + 4 bot KOs: Bot Buster", Achievements.has(id, "bot_10") && Achievements.counter(id, "bot_kos") == 10 && !Achievements.has(id, "bot_100"));
			Achievements.distinct(id, "Carl", "kit_wins", "k1");
			Achievements.distinct(id, "Carl", "kit_wins", "k1");
			Achievements.distinct(id, "Carl", "kit_wins", "k2");
			check("the same kit twice counts once", Achievements.counter(id, "kit_wins") == 2 && !Achievements.has(id, "kits_3"));
			Achievements.distinct(id, "Carl", "kit_wins", "k3");
			check("three different kits: Versatile", Achievements.has(id, "kits_3"));
			Achievements.max(id, "Carl", "wins", 0);
			check("a lower value never lowers a max counter", Achievements.counter(id, "wins") == 1);
			Achievements.max(id, "Carl", "elo", 1050);
			check("1050 Elo is not enough", !Achievements.has(id, "elo_1100"));
			Achievements.max(id, "Carl", "elo", 1210);
			check("1210 Elo: Rising Star and Expert, not Master", Achievements.has(id, "elo_1100") && Achievements.has(id, "elo_1200") && !Achievements.has(id, "elo_1400"));
			Achievements.max(id, "Carl", "wins", 100);
			check("100 wins: all the win achievements", Achievements.has(id, "wins_10") && Achievements.has(id, "wins_25") && Achievements.has(id, "wins_50") && Achievements.has(id, "wins_100"));
			check("Alice, whose data was thrown away, has nothing", Achievements.unlockedCount(a.getUUID()) == 0);
		});
		step("achievements: the player is told a few seconds later", () -> {
			check("not told yet", !carl.heard("Achievement unlocked: Legend"));
		}, () -> carl.heard("Achievement unlocked: Legend"), 400);
		step("achievements: what the message says", () -> {
			check("it names the title and offers [WEAR]", carl.heard("New title: [Legend]") && carl.heard("[WEAR]"));
			check("nothing is left to tell", Achievements.pendingCount(carl.getUUID()) == 0);
		});
		step("achievements: the list of achievements", () -> {
			run(carl, "achievements");
			check("the menu shows unlocked and locked ones", menuHas(carl, "Legend") && menuHas(carl, "First Victory") && menuHas(carl, "Unstoppable") && menuHas(carl, "Bot Crusher"));
			check("and the way to the titles", menuHas(carl, "My titles"));
			menuStep(carl, "My titles");
		});
		step("achievements: wearing a title", () -> {
			check("the titles menu has the won ones only", menuHas(carl, "[Rookie]") && menuHas(carl, "[Legend]") && menuHas(carl, "[Versatile]") && !menuHas(carl, "[Unstoppable]"));
			check("click [Legend]", clickMenu(carl, "[Legend]"));
		});
		step("achievements: the title is in front of the name", () -> {
			var board = server.getScoreboard();
			var team = board.getPlayersTeam(carl.getScoreboardName());
			check("Carl is in a title team", team != null, "no team");
			check("its prefix is [Legend]", team != null && team.getPlayerPrefix().getString().equals("[Legend] "), team == null ? "" : team.getPlayerPrefix().getString());
			check("so the name shown in chat has it", carl.getDisplayName().getString().contains("[Legend] Carl"), carl.getDisplayName().getString());
			check("the game remembers it", Achievements.titleOf(carl.getUUID()) != null && Achievements.titleOf(carl.getUUID()).id().equals("wins_100"));
			run(carl, "titles wear streak_10");
			check("a title you haven't won is refused", carl.heard("You haven't unlocked that title yet") && Achievements.titleOf(carl.getUUID()).id().equals("wins_100"));
			run(carl, "titles wear first_win");
			check("another one can be worn", Achievements.titleOf(carl.getUUID()).title().equals("Rookie") && carl.getDisplayName().getString().contains("[Rookie] Carl"), carl.getDisplayName().getString());
			run(carl, "profile");
			check("/profile shows the title", carl.heard("Profile: [Rookie] Carl"));
			check("/profile counts them", carl.heard("Achievements: "));
		});
		step("achievements: it is saved in the file", () -> {
			Achievements.reloadForTest();
			check("the unlocked achievements are still there", Achievements.has(carl.getUUID(), "wins_100") && Achievements.has(carl.getUUID(), "bot_10") && Achievements.counter(carl.getUUID(), "kit_wins") == 3);
			check("so is the title", Achievements.titleOf(carl.getUUID()) != null && Achievements.titleOf(carl.getUUID()).title().equals("Rookie"));
			run(carl, "titles off");
			check("/titles off takes it off", Achievements.titleOf(carl.getUUID()) == null && server.getScoreboard().getPlayersTeam(carl.getScoreboardName()) == null
				&& !carl.getDisplayName().getString().contains("["));
		});
		step("achievements: clean up", () -> {
			Achievements.resetForTest();
			check("Carl has no team left", server.getScoreboard().getPlayersTeam(carl.getScoreboardName()) == null);
		});

		// ---------------------------------------------------------------- victory effects and daily missions
		java.time.LocalDate quietDay = java.time.LocalDate.of(2031, 5, 5);
		while (!Missions.bonusOf(quietDay).id().equals("team1")) quietDay = quietDay.plusDays(1); // a bonus these tests never complete by accident
		final java.time.LocalDate day1 = quietDay;
		final UUID loadoutId = UUID.nameUUIDFromBytes("loadout".getBytes());
		final UUID missionId = UUID.nameUUIDFromBytes("mission-player".getBytes());
		final int[] fx = new int[8];

		step("effects: the catalog of things to win", () -> {
			Effects.resetForTest();
			Missions.resetForTest();
			Victory.resetForTest();
			var all = Particles.all();
			check("there are many particles (" + all.size() + ")", all.size() > 50);
			check("flame and the colored dust exist", Particles.find("flame") != null && Particles.find("dust_red") != null && Particles.find("dust_black") != null
				&& Particles.find("heart") != null && Particles.find("end_rod") != null && Particles.find("happy_villager") != null);
			check("the ones that cover the screen do not", Particles.find("elder_guardian") == null && Particles.find("explosion_emitter") == null && Particles.find("sonic_boom") == null);
			check("no two particles share an id or a name", all.stream().map(Particles.Def::id).distinct().count() == all.size() && all.stream().map(Particles.Def::name).distinct().count() == all.size());
			check("23 firework things: 16 colors, 5 shapes, the trail and the twinkle", Effects.allFireworkItems().size() == 23 && Effects.COLORS.length == 16 && Effects.COLOR_RGB.length == 16
				&& Effects.SHAPES.length == 5);
			check("6 explosions with a name each", Effects.EXPLOSIONS.length == 6 && Effects.EXPLOSION_NAMES.length == 6 && Effects.EXPLOSION_NAMES[0].equals("Orbital Strike Cannon")
				&& Effects.EXPLOSION_NAMES[3].equals("Shooting Star") && Effects.EXPLOSION_NAMES[4].equals("Black Hole") && Effects.EXPLOSION_NAMES[5].equals("Meteor Shower"));
			check("3 fireworks, 2 floor slots, 4 arena slots, 5 colors", Effects.FIREWORK_SLOTS == 3 && Effects.FLOOR_SLOTS == 2 && Effects.AREA_SLOTS == 4 && Effects.MAX_COLORS == 5);
			check("the chances are the ones asked for", Effects.fireworkChance == 0.70 && Effects.particleChance == 0.40 && Effects.explosionChance == 0.008 && Effects.weeklyExplosionChance == 0.015 && Effects.explosionPity == 100);
		});
		step("effects: a new player owns almost nothing", () -> {
			UUID id = UUID.nameUUIDFromBytes("fresh-player".getBytes());
			check("only the white color and the small ball", Effects.ownedItemCount(id) == 2 && Effects.ownsItem(id, "color:white") && Effects.ownsItem(id, "shape:small_ball"));
			check("white is on the firework behind you, the other two are empty", Effects.fireworkColors(id, 0).equals(List.of("white")) && Effects.fireworkColors(id, 1).isEmpty()
				&& Effects.fireworkColors(id, 2).isEmpty());
			check("no particles and no explosions", Effects.ownedParticles(id).isEmpty() && Effects.ownedExplosions(id).isEmpty());
			check("every particle slot is empty", Effects.particleSlot(id, false, 0).isEmpty() && Effects.particleSlot(id, false, 1).isEmpty() && Effects.particleSlot(id, true, 0).isEmpty()
				&& Effects.particleSlot(id, true, 3).isEmpty());
			check("no explosion is equipped and other players' effects are shown", Effects.explosionSlot(id).isEmpty() && !Effects.hideOthers(id));
		});
		step("effects: the reward of a mission", () -> {
			Effects.useRandom(new java.util.Random(1234));
			UUID id = UUID.nameUUIDFromBytes("reward-player".getBytes());
			Effects.fireworkChance = 1;
			Effects.particleChance = 0;
			Effects.explosionChance = 0;
			List<Effects.Gained> first = Effects.roll(id, "Rewarded");
			check("with a 100% firework chance a mission gives one new firework thing", first.size() == 1 && first.get(0).kind().equals("firework") && Effects.ownedItemCount(id) == 3, first.toString());
			List<String> given = new ArrayList<>();
			given.add(first.get(0).id());
			boolean distinct = true;
			for (int i = 0; i < 20; i++) {
				List<Effects.Gained> g = Effects.roll(id, "Rewarded");
				if (g.size() != 1 || !g.get(0).kind().equals("firework") || given.contains(g.get(0).id())) distinct = false;
				else given.add(g.get(0).id());
			}
			check("21 missions give 21 different things, so everything gets unlocked", distinct && given.size() == 21 && Effects.ownedItemCount(id) == Effects.allFireworkItems().size(),
				given.size() + " " + Effects.ownedItemCount(id));
			List<Effects.Gained> rest = Effects.roll(id, "Rewarded");
			check("when there is nothing left to unlock the firework roll becomes a particle", rest.size() == 1 && rest.get(0).kind().equals("particle") && Effects.ownedParticles(id).size() == 1, rest.toString());

			UUID none = UUID.nameUUIDFromBytes("consolation".getBytes());
			Effects.fireworkChance = 0;
			Effects.particleChance = 0;
			List<Effects.Gained> consolation = Effects.roll(none, "Consoled");
			check("a mission that rolls nothing still gives a particle", consolation.size() == 1 && consolation.get(0).kind().equals("particle") && Effects.ownedItemCount(none) == 2, consolation.toString());

			UUID both = UUID.nameUUIDFromBytes("both".getBytes());
			Effects.fireworkChance = 1;
			Effects.particleChance = 1;
			List<Effects.Gained> two = Effects.roll(both, "Lucky");
			check("both rolls can hit: a firework thing and a particle", two.size() == 2 && two.get(0).kind().equals("firework") && two.get(1).kind().equals("particle"), two.toString());

			UUID boom = UUID.nameUUIDFromBytes("explosions".getBytes());
			Effects.fireworkChance = 0;
			Effects.particleChance = 0;
			Effects.explosionChance = 1;
			boolean allExplosions = true;
			List<String> blasts = new ArrayList<>();
			for (int i = 0; i < Effects.EXPLOSIONS.length; i++) {
				List<Effects.Gained> g = Effects.roll(boom, "Boom");
				if (g.size() != 1 || !g.get(0).kind().equals("explosion") || blasts.contains(g.get(0).id())) allExplosions = false;
				else blasts.add(g.get(0).id());
			}
			check("six lucky missions give the six different explosions", allExplosions && Effects.ownedExplosions(boom).size() == Effects.EXPLOSIONS.length, blasts.toString());
			List<Effects.Gained> afterBlasts = Effects.roll(boom, "Boom");
			check("with all of them owned a lucky roll gives a particle instead", afterBlasts.size() == 1 && afterBlasts.get(0).kind().equals("particle"), afterBlasts.toString());

			UUID pity = UUID.nameUUIDFromBytes("pity".getBytes());
			Effects.explosionChance = 0;
			Effects.explosionPity = 3;
			boolean[] got = new boolean[5];
			for (int i = 0; i < 5; i++) {
				boolean explosion = false;
				for (Effects.Gained g : Effects.roll(pity, "Patient")) if (g.kind().equals("explosion")) explosion = true;
				got[i] = explosion;
			}
			check("the third mission without luck gives the explosion for sure (pity), then it starts over", !got[0] && !got[1] && got[2] && !got[3] && !got[4],
				java.util.Arrays.toString(got));

			UUID copies = UUID.nameUUIDFromBytes("copies".getBytes());
			Effects.unlockAll(copies);
			int before = 0;
			for (int n : Effects.ownedParticles(copies).values()) before += n;
			Effects.explosionPity = 100;
			Effects.fireworkChance = 0;
			Effects.particleChance = 1;
			List<Effects.Gained> dupes = Effects.roll(copies, "Collector");
			int after = 0;
			for (int n : Effects.ownedParticles(copies).values()) after += n;
			check("a repeated particle is a new copy, and the message says so", dupes.size() == 1 && dupes.get(0).duplicate() && dupes.get(0).label().contains("(now x4)") && after == before + 1,
				dupes + " " + before + "->" + after);
			check("/effects unlockall style: 3 copies of every particle", before == 3 * Particles.all().size() && Effects.ownedItemCount(copies) == 23 && Effects.ownedExplosions(copies).size() == Effects.EXPLOSIONS.length);
		});
		step("effects: what can be equipped", () -> {
			UUID id = loadoutId;
			check("a locked color is refused", Effects.toggleColor(id, 1, "red") != null && Effects.fireworkColors(id, 1).isEmpty());
			check("a color that does not exist is refused", Effects.toggleColor(id, 1, "plaid") != null);
			for (String c : new String[] {"red", "yellow", "blue", "green", "pink"}) Effects.grantItem(id, "color:" + c);
			boolean ok = Effects.toggleColor(id, 0, "red") == null && Effects.toggleColor(id, 0, "yellow") == null && Effects.toggleColor(id, 0, "blue") == null
				&& Effects.toggleColor(id, 0, "green") == null;
			check("white and four more make 5 colors on one firework", ok && Effects.fireworkColors(id, 0).size() == 5, String.valueOf(Effects.fireworkColors(id, 0)));
			String sixth = Effects.toggleColor(id, 0, "pink");
			check("a sixth color is refused", sixth != null && sixth.contains("up to 5 colors") && Effects.fireworkColors(id, 0).size() == 5, String.valueOf(sixth));
			check("taking one off makes room", Effects.toggleColor(id, 0, "white") == null && Effects.fireworkColors(id, 0).size() == 4 && Effects.toggleColor(id, 0, "pink") == null
				&& Effects.fireworkColors(id, 0).size() == 5 && !Effects.fireworkColors(id, 0).contains("white"));
			check("the same color can be on two fireworks", Effects.toggleColor(id, 1, "red") == null && Effects.fireworkColors(id, 1).equals(List.of("red")) && Effects.fireworkColors(id, 2).isEmpty());

			check("a locked shape is refused", Effects.setShape(id, 1, "star") != null && Effects.fireworkShape(id, 1).equals("small_ball"));
			Effects.grantItem(id, "shape:star");
			check("an unlocked shape goes to that firework only", Effects.setShape(id, 1, "star") == null && Effects.fireworkShape(id, 1).equals("star")
				&& Effects.fireworkShape(id, 0).equals("small_ball") && Effects.fireworkShape(id, 2).equals("small_ball"));
			check("a shape that does not exist is refused", Effects.setShape(id, 1, "heart") != null && Effects.fireworkShape(id, 1).equals("star"));
			check("the trail and the twinkle are locked at first", Effects.toggleTrail(id, 2) != null && Effects.toggleTwinkle(id, 2) != null);
			Effects.grantItem(id, "trail");
			Effects.grantItem(id, "twinkle");
			check("and switch on and off after that", Effects.toggleTrail(id, 2) == null && Effects.fireworkTrail(id, 2) && !Effects.fireworkTrail(id, 1)
				&& Effects.toggleTrail(id, 2) == null && !Effects.fireworkTrail(id, 2) && Effects.toggleTwinkle(id, 0) == null && Effects.fireworkTwinkle(id, 0));

			check("a particle you don't have is refused", Effects.setParticle(id, false, 0, "flame") != null && Effects.particleSlot(id, false, 0).isEmpty());
			Effects.grantParticle(id, "flame");
			check("with one copy it fits in one slot", Effects.setParticle(id, false, 0, "flame") == null && Effects.particleSlot(id, false, 0).equals("flame"));
			String second = Effects.setParticle(id, true, 0, "flame");
			check("a second slot is refused with one copy", second != null && second.contains("only have 1 copy") && Effects.particleSlot(id, true, 0).isEmpty(), String.valueOf(second));
			check("the same slot again is fine", Effects.setParticle(id, false, 0, "flame") == null);
			Effects.grantParticle(id, "flame");
			check("a duplicate gives a second slot", Effects.setParticle(id, true, 0, "flame") == null && Effects.equippedCount(id, "flame") == 2 && Effects.particleCount(id, "flame") == 2);
			check("and a third one is refused", Effects.setParticle(id, true, 1, "flame") != null && Effects.equippedCount(id, "flame") == 2);
			check("a slot that does not exist is refused", Effects.setParticle(id, true, 9, "flame") != null && Effects.setParticle(id, false, 2, "flame") != null);
			check("an empty id empties the slot", Effects.setParticle(id, true, 0, "") == null && Effects.particleSlot(id, true, 0).isEmpty() && Effects.equippedCount(id, "flame") == 1);
			for (int i = 0; i < 10; i++) Effects.grantParticle(id, "flame");
			check("you can own up to 6 copies", Effects.MAX_COPIES == 6 && Effects.particleCount(id, "flame") == 6);

			check("a locked explosion is refused", Effects.setExplosion(id, "orbital_strike") != null && Effects.explosionSlot(id).isEmpty());
			check("an explosion that does not exist is refused", Effects.setExplosion(id, "big_bang") != null);
			Effects.grantExplosion(id, "orbital_strike");
			check("an unlocked explosion can be equipped and taken off", Effects.setExplosion(id, "orbital_strike") == null && Effects.explosionSlot(id).equals("orbital_strike")
				&& Effects.setExplosion(id, "") == null && Effects.explosionSlot(id).isEmpty());
			check("equipping an explosion cleared the colors and the particles, which is the rule", !Effects.hasEffects(id));
			for (String c : new String[] {"red", "yellow", "blue", "green", "pink"}) Effects.toggleColor(id, 0, c);
			Effects.toggleColor(id, 1, "red");
			Effects.setParticle(id, false, 0, "flame");
			Effects.setHideOthers(id, true);
		});
		step("effects: effects OR an explosion, never both", () -> {
			UUID id = UUID.nameUUIDFromBytes("either-or".getBytes());
			Effects.grantItem(id, "color:red");
			Effects.grantParticle(id, "flame");
			Effects.grantExplosion(id, "orbital_strike");
			Effects.grantExplosion(id, "domain_expansion");
			Effects.grantExplosion(id, "orbital_wither");
			check("a new player has the first firework and no explosion", Effects.hasEffects(id) && Effects.explosionSlot(id).isEmpty() && !Victory.explosionMode(id));
			check("a color and a particle go on", Effects.toggleColor(id, 1, "red") == null && Effects.setParticle(id, false, 0, "flame") == null);
			check("equipping an explosion takes every color and every particle off", Effects.setExplosion(id, "orbital_strike") == null && Effects.explosionSlot(id).equals("orbital_strike")
				&& !Effects.hasEffects(id) && Effects.fireworkColors(id, 0).isEmpty() && Effects.fireworkColors(id, 1).isEmpty() && Effects.particleSlot(id, false, 0).isEmpty());
			check("but everything stays owned", Effects.ownsItem(id, "color:red") && Effects.particleCount(id, "flame") == 1 && Effects.ownsItem(id, "color:white"));
			check("with an explosion on, the fireworks and particles are not played", Victory.explosionMode(id));
			check("another explosion replaces the first", Effects.setExplosion(id, "domain_expansion") == null && Effects.explosionSlot(id).equals("domain_expansion"));
			check("equipping a color takes the explosion off", Effects.toggleColor(id, 0, "red") == null && Effects.explosionSlot(id).isEmpty() && Effects.fireworkColors(id, 0).equals(List.of("red"))
				&& !Victory.explosionMode(id));
			check("equipping a particle takes it off too", Effects.setExplosion(id, "orbital_strike") == null && Effects.setParticle(id, true, 0, "flame") == null
				&& Effects.explosionSlot(id).isEmpty() && Effects.particleSlot(id, true, 0).equals("flame"));
			check("taking an explosion off leaves nothing equipped", Effects.setExplosion(id, "domain_expansion") == null && Effects.setExplosion(id, "") == null && !Effects.hasEffects(id)
				&& Effects.explosionSlot(id).isEmpty());
			Effects.setExplosion(id, "orbital_wither");
			Effects.reloadForTest();
			check("it is saved like that", Effects.explosionSlot(id).equals("orbital_wither") && !Effects.hasEffects(id));
			Effects.setExplosion(id, "");
		});
		step("effects: it is saved in the file", () -> {
			UUID id = loadoutId;
			Effects.reloadForTest();
			check("the colors are still on the fireworks", Effects.fireworkColors(id, 0).equals(List.of("red", "yellow", "blue", "green", "pink")) && Effects.fireworkColors(id, 1).equals(List.of("red")));
			check("so are the shape, the trail and the twinkle", Effects.fireworkShape(id, 1).equals("star") && Effects.fireworkTwinkle(id, 0) && !Effects.fireworkTrail(id, 2));
			check("the particles and their copies", Effects.particleSlot(id, false, 0).equals("flame") && Effects.particleCount(id, "flame") == 6);
			check("the explosion you own and the option to hide others", Effects.ownsExplosion(id, "orbital_strike") && Effects.explosionSlot(id).isEmpty() && Effects.hideOthers(id));
			check("what a new player gets is saved too", Effects.ownedItemCount(UUID.nameUUIDFromBytes("reward-player".getBytes())) == 23);
		});

		// ---------------------------------------------------------------- daily missions
		step("missions: the six missions of a day", () -> {
			Effects.resetForTest();
			Missions.setDayForTest(day1);
			List<Missions.Def> today = Missions.today(missionId);
			check("5 fixed missions and 1 bonus", today.size() == 6 && Missions.fixed().size() == 5);
			check("the five of every day, in order", Missions.fixed().get(0).id().equals("heal100") && Missions.fixed().get(1).id().equals("combo15") && Missions.fixed().get(2).id().equals("eat40")
				&& Missions.fixed().get(3).id().equals("potions10") && Missions.fixed().get(4).id().equals("streak3"));
			check("with the goals you asked for (100 hearts, combo 15, 40 foods, 10 potions, 3 wins)", Missions.fixed().get(0).goalInt() == 100 && Missions.fixed().get(1).goalInt() == 15
				&& Missions.fixed().get(2).goalInt() == 40 && Missions.fixed().get(3).goalInt() == 10 && Missions.fixed().get(4).goalInt() == 3);
			check("the sixth is the bonus of the date", today.get(5).id().equals(Missions.bonusOf(day1).id()) && today.get(5).id().equals("team1"));
			java.util.Set<String> week = new java.util.HashSet<>();
			for (int i = 0; i < 7; i++) week.add(Missions.bonusOf(day1.plusDays(i)).id());
			check("the bonus changes every day and a week shows all " + Missions.bonusPool().size() + " of them", week.size() == Missions.bonusPool().size()
				&& !Missions.bonusOf(day1).id().equals(Missions.bonusOf(day1.plusDays(1)).id()), week.toString());
			check("the same date always has the same bonus", Missions.bonusOf(day1).id().equals(Missions.bonusOf(day1).id()));
		});
		step("missions: progress, rewards and no double payment", () -> {
			Effects.fireworkChance = 1;
			Effects.particleChance = 0;
			Effects.explosionChance = 0;
			Missions.setDayForTest(day1);
			Missions.Def heal = Missions.fixed().get(0);
			Missions.Def combo = Missions.fixed().get(1);
			Missions.Def eat = Missions.fixed().get(2);
			Missions.Def potions = Missions.fixed().get(3);
			Missions.Def streak = Missions.fixed().get(4);
			Missions.Facts f1 = new Missions.Facts();
			f1.won = true;
			f1.kit = "k1";
			f1.combo = 9;
			f1.hearts = 60;
			f1.foods = 25;
			f1.potions = 4;
			f1.hits = 30;
			f1.roundsWon = 2;
			List<Missions.Completion> c1 = Missions.record(missionId, "Mia", f1);
			check("nothing is complete after the first duel", c1.isEmpty() && Missions.doneCount(missionId) == 0);
			check("the progress adds up (hearts, foods, potions), keeps the best (combo) and counts the win run",
				Missions.progress(missionId, heal) == 60 && Missions.progress(missionId, combo) == 9 && Missions.progress(missionId, eat) == 25
					&& Missions.progress(missionId, potions) == 4 && Missions.progress(missionId, streak) == 1);
			Missions.Facts f2 = new Missions.Facts();
			f2.won = true;
			f2.kit = "k2";
			f2.combo = 15;
			f2.hearts = 45;
			f2.foods = 20;
			f2.potions = 7;
			f2.hits = 20;
			f2.roundsWon = 1;
			List<Missions.Completion> c2 = Missions.record(missionId, "Mia", f2);
			java.util.Set<String> ids = new java.util.TreeSet<>();
			boolean paid = true;
			for (Missions.Completion c : c2) {
				ids.add(c.mission().id());
				if (c.rewards().isEmpty() || !c.rewards().get(0).kind().equals("firework")) paid = false;
			}
			check("the second duel completes four missions", c2.size() == 4 && ids.equals(new java.util.TreeSet<>(List.of("heal100", "combo15", "eat40", "potions10"))), ids.toString());
			check("each one paid a reward", paid);
			check("so the player owns 4 new firework things", Effects.ownedItemCount(missionId) == 2 + 4, "" + Effects.ownedItemCount(missionId));
			check("four are done today and the win run is 2", Missions.doneCount(missionId) == 4 && Missions.isDone(missionId, heal) && !Missions.isDone(missionId, streak) && Missions.progress(missionId, streak) == 2);
			Missions.Facts f3 = new Missions.Facts();
			f3.lost = true;
			f3.hearts = 500;
			f3.foods = 500;
			List<Missions.Completion> c3 = Missions.record(missionId, "Mia", f3);
			check("a loss ends the win run", c3.isEmpty() && Missions.progress(missionId, streak) == 0);
			check("and a mission that is done never pays twice", Effects.ownedItemCount(missionId) == 6 && Missions.doneCount(missionId) == 4);
			Missions.Facts win = new Missions.Facts();
			win.won = true;
			Missions.record(missionId, "Mia", win);
			Missions.record(missionId, "Mia", win);
			check("two wins: 2 of 3", Missions.progress(missionId, streak) == 2 && !Missions.isDone(missionId, streak));
			Missions.record(missionId, "Mia", new Missions.Facts());
			check("a tie breaks the run", Missions.progress(missionId, streak) == 0);
			Missions.record(missionId, "Mia", win);
			Missions.record(missionId, "Mia", win);
			check("two wins after the tie: 2 of 3", Missions.progress(missionId, streak) == 2 && !Missions.isDone(missionId, streak));
			List<Missions.Completion> c4 = Missions.record(missionId, "Mia", win);
			check("the third win in a row completes it", c4.size() == 1 && c4.get(0).mission().id().equals("streak3") && Missions.isDone(missionId, streak) && Missions.doneCount(missionId) == 5);
			check("and gives its reward", c4.get(0).rewards().size() == 1 && Effects.ownedItemCount(missionId) == 7);
			check("five rewards wait to be told", Missions.pendingCount(missionId) == 5, "" + Missions.pendingCount(missionId));
		});
		step("missions: a new day starts from zero", () -> {
			Missions.setDayForTest(day1.plusDays(1));
			Missions.Def heal = Missions.fixed().get(0);
			Missions.Def streak = Missions.fixed().get(4);
			check("nothing is done and there is no progress", Missions.doneCount(missionId) == 0 && Missions.progress(missionId, heal) == 0 && !Missions.isDone(missionId, streak));
			check("what was won stays", Effects.ownedItemCount(missionId) == 7);
			Missions.Facts win = new Missions.Facts();
			win.won = true;
			Missions.record(missionId, "Mia", win);
			check("the win run starts again", Missions.progress(missionId, streak) == 1);
			check("the bonus of the new day is another one", !Missions.today(missionId).get(5).id().equals(Missions.bonusOf(day1).id()));
			Missions.setDayForTest(day1);
		});
		step("missions: every bonus mission can be completed", () -> {
			for (Missions.Def bonus : Missions.bonusPool()) {
				java.time.LocalDate d = day1;
				while (!Missions.bonusOf(d).id().equals(bonus.id())) d = d.plusDays(1);
				Missions.setDayForTest(d);
				UUID id = UUID.nameUUIDFromBytes(("bonus-" + bonus.id()).getBytes());
				List<Missions.Facts> before = new ArrayList<>();
				Missions.Facts last = new Missions.Facts();
				switch (bonus.id()) {
					case "duels5" -> {
						for (int i = 0; i < 4; i++) before.add(new Missions.Facts());
					}
					case "hits100" -> {
						Missions.Facts f = new Missions.Facts();
						f.hits = 99;
						before.add(f);
						last.hits = 1;
					}
					case "pearls5" -> {
						Missions.Facts f = new Missions.Facts();
						f.pearls = 4;
						before.add(f);
						last.pearls = 1;
					}
					case "apples5" -> {
						Missions.Facts f = new Missions.Facts();
						f.apples = 4;
						before.add(f);
						last.apples = 1;
					}
					case "rounds10" -> {
						Missions.Facts f = new Missions.Facts();
						f.roundsWon = 9;
						before.add(f);
						last.roundsWon = 1;
					}
					case "kits2" -> {
						for (int i = 0; i < 2; i++) {
							Missions.Facts f = new Missions.Facts();
							f.won = true;
							f.kit = "x";
							before.add(f);
						}
						last.won = true;
						last.kit = "y";
					}
					case "team1" -> {
						Missions.Facts f = new Missions.Facts();
						f.won = true;
						before.add(f);
						last.won = true;
						last.team = true;
					}
					default -> {
					}
				}
				for (Missions.Facts f : before) Missions.record(id, "Bonus", f);
				boolean early = Missions.isDone(id, bonus);
				List<Missions.Completion> end = Missions.record(id, "Bonus", last);
				boolean mine = false;
				for (Missions.Completion c : end) if (c.mission().id().equals(bonus.id())) mine = true;
				check("bonus '" + bonus.text() + "': not done before the last duel, done after it", !early && mine && Missions.isDone(id, bonus));
			}
			Missions.setDayForTest(day1);
		});
		step("missions: the player is told a few seconds later", () -> {
			Effects.resetForTest();
			Effects.fireworkChance = 1;
			Effects.particleChance = 0;
			Effects.explosionChance = 0;
			Missions.setDayForTest(day1);
			carl.inbox.clear();
			Missions.Facts f = new Missions.Facts();
			f.won = true;
			f.combo = 15;
			List<Missions.Completion> c = Missions.record(carl.getUUID(), "Carl", f);
			check("Carl completed the combo mission", c.size() == 1 && c.get(0).mission().id().equals("combo15"));
			check("he has a reward to be told about, but not yet", Missions.pendingCount(carl.getUUID()) == 1 && !carl.heard("Mission complete:"));
		}, () -> carl.heard("Mission complete: Land a 15-hit combo in one duel"), 400);
		step("missions: what the message says", () -> {
			check("it names the reward and offers [EFFECTS]", carl.heard("Reward: ") && carl.heard("Firework") && carl.heard("[EFFECTS]"));
			check("nothing is left to tell", Missions.pendingCount(carl.getUUID()) == 0);
		});

		// ---------------------------------------------------------------- a real win
		step("victory: Alice gets her effects ready and plays for the third win in a row", () -> {
			Effects.resetForTest();
			Effects.fireworkChance = 1;
			Effects.particleChance = 0;
			Effects.explosionChance = 0;
			Missions.setDayForTest(day1);
			Victory.resetForTest();
			a.inbox.clear();
			b.inbox.clear();
			UUID id = a.getUUID();
			for (String c : new String[] {"red", "blue", "yellow", "lime", "cyan", "pink", "purple", "orange"}) Effects.grantItem(id, "color:" + c);
			Effects.grantItem(id, "shape:star");
			Effects.grantItem(id, "trail");
			String problems = join(Effects.toggleColor(id, 0, "red"), Effects.toggleColor(id, 0, "blue"),
				Effects.toggleColor(id, 1, "yellow"), Effects.toggleColor(id, 1, "lime"), Effects.toggleColor(id, 1, "cyan"), Effects.toggleColor(id, 1, "pink"), Effects.toggleColor(id, 1, "purple"),
				Effects.toggleColor(id, 2, "orange"), Effects.setShape(id, 1, "star"), Effects.toggleTrail(id, 1));
			for (String p : new String[] {"flame", "flame", "heart", "end_rod", "happy_villager", "dust_red"}) Effects.grantParticle(id, p);
			problems += join(Effects.setParticle(id, false, 0, "flame"), Effects.setParticle(id, false, 1, "heart"), Effects.setParticle(id, true, 0, "end_rod"),
				Effects.setParticle(id, true, 1, "happy_villager"), Effects.setParticle(id, true, 2, "dust_red"), Effects.setParticle(id, true, 3, "flame"));
			Effects.grantExplosion(id, "orbital_strike");
			check("Alice equipped everything without errors", problems.isEmpty(), problems);
			check("three fireworks with 3, 5 and 1 colors, a star with trail in the middle", Effects.fireworkColors(id, 0).size() == 3 && Effects.fireworkColors(id, 1).size() == 5
				&& Effects.fireworkColors(id, 2).size() == 1 && Effects.fireworkShape(id, 1).equals("star") && Effects.fireworkTrail(id, 1));
			fx[0] = Effects.ownedItemCount(id);
			Missions.Facts won = new Missions.Facts();
			won.won = true;
			Missions.record(id, "Alice", won);
			Missions.record(id, "Alice", won);
			check("she has won twice in a row today", Missions.progress(id, Missions.fixed().get(4)) == 2 && Missions.pendingCount(id) == 0);
			check("Bob has nothing but the first things", Effects.ownedItemCount(b.getUUID()) == 2);
			invite(a, b, new DuelConfig("qkit", false, WinMode.FIRST_TO, 1));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 800);
		step("victory: Alice wins the duel", () -> {
			hit(a, b);
		}, () -> Victory.showsStarted() >= 1, 600);
		step("victory: the fireworks went up", () -> {
			check("Alice's three fireworks went up and Bob's did not", Victory.fireworkLaunches() == 3 && Victory.showsStarted() == 1, Victory.fireworkLaunches() + " / " + Victory.showsStarted());
			int rockets = 0;
			for (net.minecraft.world.entity.Entity e : ((ServerLevel) a.level()).getAllEntities()) if (e instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity) rockets++;
			check("the rockets are flying in the arena", rockets >= 1, "" + rockets);
			check("the particles are playing", Victory.activeShows() == 1);
			fx[1] = particlePackets(a);
			fx[2] = particlePackets(b);
		}, () -> particlePackets(a) > fx[1] + 3 && particlePackets(b) > fx[2] + 3, 200);
		step("victory: both of them saw the particles", () -> {
			check("Alice saw them", particlePackets(a) > fx[1] + 3, particlePackets(a) + " vs " + fx[1]);
			check("Bob, in the same arena, saw them too", particlePackets(b) > fx[2] + 3, particlePackets(b) + " vs " + fx[2]);
		}, () -> Victory.activeShows() == 0, 600);
		step("victory: the show ends by itself", () -> {
			check("no show is left", Victory.activeShows() == 0);
		}, () -> noDuel(a) && noDuel(b), 800);
		step("victory: the third win completed a mission", () -> {
			UUID id = a.getUUID();
			check("'Win 3 duels in a row' is done", Missions.isDone(id, Missions.fixed().get(4)) && Missions.doneCount(id) == 1, Missions.doneCount(id) + " done");
			check("its reward was one new firework thing", Effects.ownedItemCount(id) == fx[0] + 1, fx[0] + " -> " + Effects.ownedItemCount(id));
			check("Bob lost: his run is 0 and he got nothing", Missions.progress(b.getUUID(), Missions.fixed().get(4)) == 0 && Effects.ownedItemCount(b.getUUID()) == 2 && Missions.pendingCount(b.getUUID()) == 0);
			check("only Alice's fireworks went up", Victory.fireworkLaunches() == 3);
		}, () -> a.heard("Mission complete: Win 3 duels in a row"), 400);
		step("victory: the reward message", () -> {
			check("it has the reward and the button", a.heard("Reward: Firework") && a.heard("[EFFECTS]"));
			check("nothing is left to tell", Missions.pendingCount(a.getUUID()) == 0);
			check("Bob heard nothing about missions", !b.heard("Mission complete"));
		});

		// ---------------------------------------------------------------- preview and hiding
		step("effects: /effects preview, and who sees it", () -> {
			ServerLevel lvl = (ServerLevel) a.level();
			b.teleportTo(lvl, a.getX() + 3, a.getY(), a.getZ(), java.util.Set.of(), 0f, 0f, true);
			carl.teleportTo(lvl, a.getX(), a.getY(), a.getZ() + 3, java.util.Set.of(), 0f, 0f, true);
			check("Alice, Bob and Carl are in the same world", a.level() == b.level() && a.level() == carl.level(), where(a) + " / " + where(b) + " / " + where(carl));
			Effects.setHideOthers(b.getUUID(), true);
			Victory.resetForTest();
			fx[1] = particlePackets(a);
			fx[2] = particlePackets(b);
			fx[3] = particlePackets(carl);
			run(a, "effects preview");
			check("the preview started her three fireworks and her show", Victory.fireworkLaunches() == 3 && Victory.showsStarted() == 1 && Victory.activeShows() == 1,
				Victory.fireworkLaunches() + " / " + Victory.showsStarted() + " / " + Victory.activeShows());
		}, () -> particlePackets(a) > fx[1] + 5 && particlePackets(carl) > fx[3], 200);
		step("effects: Bob hides other players' effects", () -> {
			check("Alice saw her own particles", particlePackets(a) > fx[1]);
			check("Carl, next to her, saw them", particlePackets(carl) > fx[3]);
			check("Bob hid other players' effects: he saw none", particlePackets(b) == fx[2], particlePackets(b) + " vs " + fx[2]);
			Effects.setHideOthers(b.getUUID(), false);
		}, () -> Victory.activeShows() == 0, 400);

		// ---------------------------------------------------------------- the menus
		step("menus: Alice's fireworks (some are locked)", () -> {
			run(a, "effects");
			check("the main menu has its options", menuHas(a, "Fireworks") && menuHas(a, "Particles") && menuHas(a, "Explosions") && menuHas(a, "Missions") && menuHas(a, "Preview")
				&& menuHas(a, "Other players' effects: shown"));
			menuStep(a, "Fireworks");
			check("the three fireworks", menuHas(a, "Middle") && menuHas(a, "Left") && menuHas(a, "Right") && menuHas(a, "Back"));
			menuStep(a, "Left");
			check("10 of the 23 things are still locked", menuCount(a, "Locked: ") == 10, "" + menuCount(a, "Locked: "));
			check("the unlocked colors and shapes are there", menuHas(a, "Orange") && menuHas(a, "Yellow") && menuHas(a, "Star") && menuHas(a, "Trail: on") && menuHas(a, "Small ball"), "");
		});
		step("menus: a sixth color does not fit", () -> {
			menuStep(a, "Orange");
			check("she was told why", a.heard("A firework has up to 5 colors"));
			check("and the firework did not change", Effects.fireworkColors(a.getUUID(), 1).size() == 5 && !Effects.fireworkColors(a.getUUID(), 1).contains("orange"));
			menuStep(a, "Yellow");
			check("taking a color off", !Effects.fireworkColors(a.getUUID(), 1).contains("yellow") && Effects.fireworkColors(a.getUUID(), 1).size() == 4);
			menuStep(a, "Orange");
			check("then the new one fits", Effects.fireworkColors(a.getUUID(), 1).contains("orange") && Effects.fireworkColors(a.getUUID(), 1).size() == 5);
			menuStep(a, "Trail: on");
			check("the trail switches off from the menu", !Effects.fireworkTrail(a.getUUID(), 1) && menuHas(a, "Trail: off"));
			menuStep(a, "Back");
			check("Back goes to the three fireworks", menuHas(a, "Middle") && menuHas(a, "Right"));
			menuStep(a, "Back");
			check("and Back again to the main menu", menuHas(a, "Fireworks") && menuHas(a, "Preview"));
		});
		step("menus: Alice's explosions and missions", () -> {
			menuStep(a, "Explosions");
			check("she owns the Orbital Strike Cannon, the other five are still unknown", menuHas(a, "Orbital Strike Cannon") && menuCount(a, "Locked: ???") == Effects.EXPLOSIONS.length - 1, "" + menuCount(a, "Locked: ???"));
			menuStep(a, "Orbital Strike Cannon");
			check("clicking an explosion equips it and takes her fireworks and particles off", Effects.explosionSlot(a.getUUID()).equals("orbital_strike") && !Effects.hasEffects(a.getUUID()));
			check("and she was told why", a.heard("Your fireworks and particles were taken off"));
			menuStep(a, "Orbital Strike Cannon");
			check("clicking an equipped explosion takes it off", Effects.explosionSlot(a.getUUID()).isEmpty());
			run(a, "missions");
			check("the six missions of the day", menuHas(a, "Regenerate 100 hearts in duels") && menuHas(a, "Land a 15-hit combo in one duel") && menuHas(a, "Eat 40 foods in duels")
				&& menuHas(a, "Drink or throw 10 potions in duels") && menuHas(a, "Win 3 duels in a row") && menuHas(a, Missions.bonusOf(day1).text()) && menuHas(a, "How it works"));
			check("and the five weekly ones", menuHas(a, "Win 15 duels this week") && menuHas(a, "Win with 4 different kits this week") && menuHas(a, "Win 5 duels in a row")
				&& menuHas(a, "Land 500 hits in duels") && menuHas(a, "Win 3 2v2 duels this week"));
			check("the one she completed is an emerald, the others are not", menuItem(a, "Win 3 duels in a row").is(Items.EMERALD) && !menuItem(a, "Eat 40 foods in duels").is(Items.EMERALD));
			a.closeContainer();
		});
		step("menus: an admin unlocks everything for Bob", () -> {
			UUID id = b.getUUID();
			run(a, "effects unlockall Bob");
			check("a normal player can't unlock everything", Effects.ownedItemCount(id) == 2 && Effects.ownedParticles(id).isEmpty() && Effects.ownedExplosions(id).isEmpty());
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "effects unlockall Bob");
			check("the console (an admin) can", Effects.ownedItemCount(id) == 23);
			check("every particle with 3 copies", Effects.ownedParticles(id).size() == Particles.all().size() && Effects.particleCount(id, "flame") == 3);
			check("and the six explosions", Effects.ownedExplosions(id).size() == Effects.EXPLOSIONS.length);
			check("Bob was told", b.heard("You got all the victory effects"));
			run(b, "effects");
			menuStep(b, "Fireworks");
			menuStep(b, "Right");
			check("nothing is locked for him", menuCount(b, "Locked: ") == 0 && menuHas(b, "Light Blue") && menuHas(b, "Twinkle: off"));
			menuStep(b, "Light Blue");
			menuStep(b, "Creeper");
			menuStep(b, "Twinkle: off");
			check("Right firework: light blue, creeper, twinkle", Effects.fireworkColors(id, 2).equals(List.of("light_blue")) && Effects.fireworkShape(id, 2).equals("creeper") && Effects.fireworkTwinkle(id, 2));
			menuStep(b, "Back");
			menuStep(b, "Back");
		});
		step("menus: Bob's particles", () -> {
			UUID id = b.getUUID();
			List<Particles.Def> sorted = new ArrayList<>(Particles.all());
			sorted.sort((x, y) -> x.name().compareToIgnoreCase(y.name()));
			menuStep(b, "Particles");
			check("six empty slots", menuHas(b, "Floor 1: empty") && menuHas(b, "Floor 2: empty") && menuHas(b, "Arena 1: empty") && menuHas(b, "Arena 4: empty"));
			menuStep(b, "Floor 1: empty");
			check("the picker has the first page in alphabetical order and a way to the next", menuHas(b, sorted.get(0).name()) && menuHas(b, sorted.get(44).name()) && !menuHas(b, sorted.get(45).name())
				&& menuHas(b, "Next page") && !menuHas(b, "Previous page"));
			menuStep(b, "Next page");
			check("page 2 starts where page 1 stopped", menuHas(b, sorted.get(45).name()) && menuHas(b, "Previous page"));
			menuStep(b, "Previous page");
			menuStep(b, sorted.get(0).name());
			check("picking a particle puts it in the slot and goes back", Effects.particleSlot(id, false, 0).equals(sorted.get(0).id()) && menuHas(b, "Floor 1: " + sorted.get(0).name()));
			menuStep(b, "Arena 2: empty");
			menuStep(b, sorted.get(0).name());
			check("the same particle in a second slot (he has 3 copies)", Effects.particleSlot(id, true, 1).equals(sorted.get(0).id()) && Effects.equippedCount(id, sorted.get(0).id()) == 2);
			menuStep(b, "Floor 1: " + sorted.get(0).name());
			menuStep(b, "Empty this slot");
			check("a slot can be emptied", Effects.particleSlot(id, false, 0).isEmpty() && menuHas(b, "Floor 1: empty") && menuHas(b, "Arena 2: " + sorted.get(0).name()));
			menuStep(b, "Back");
		});
		step("menus: Bob's explosions, the switch for other players, the missions and the preview", () -> {
			UUID id = b.getUUID();
			menuStep(b, "Explosions");
			check("nothing is unknown to Bob", menuCount(b, "Locked: ???") == 0 && menuHas(b, "Orbital Wither Cannon") && menuHas(b, "Domain Expansion"));
			menuStep(b, "Orbital Wither Cannon");
			check("Orbital Wither Cannon is equipped and his colors are off", Effects.explosionSlot(id).equals("orbital_wither") && !Effects.hasEffects(id));
			menuStep(b, "Back");
			menuStep(b, "Other players' effects: shown");
			check("the switch hides other players' effects", Effects.hideOthers(id) && menuHas(b, "Other players' effects: hidden"));
			menuStep(b, "Other players' effects: hidden");
			check("and shows them again", !Effects.hideOthers(id) && menuHas(b, "Other players' effects: shown"));
			menuStep(b, "Missions");
			check("the missions show Bob's progress", menuHas(b, "Win 3 duels in a row") && menuItem(b, "Win 3 duels in a row").is(net.minecraft.world.item.Items.BLAZE_POWDER));
			menuStep(b, "Back");
			Victory.resetForTest();
			menuStep(b, "Preview");
			check("Preview closes the menu and plays only his explosion (no fireworks, he has an explosion on)", Victory.showsStarted() == 1 && Victory.fireworkLaunches() == 0 && Victory.activeShows() == 1,
				Victory.fireworkLaunches() + " / " + Victory.showsStarted());
			check("the menu is closed", !(b.containerMenu instanceof ChestMenu));
		}, () -> Victory.activeShows() == 0, 400);
		step("effects: clean up", () -> {
			for (Dummy p : new Dummy[] {a, b, carl, dora}) p.closeContainer();
			Effects.resetForTest();
			Missions.resetForTest();
			Victory.resetForTest();
			check("everything is reset", Effects.ownedItemCount(a.getUUID()) == 2 && Missions.doneCount(a.getUUID()) == 0 && Victory.activeShows() == 0);
			Effects.resetForTest();
		});

		// ---------------------------------------------------------------- weekly missions and the midnight rule
		final UUID weekId = UUID.nameUUIDFromBytes("weekly-player".getBytes());
		final int[] weekEx = new int[1];
		step("weekly: five missions that last a week", () -> {
			Effects.resetForTest();
			Missions.resetForTest();
			check("5 weekly missions with the goals 15, 4, 5, 500 and 3", Missions.weekly().size() == 5 && Missions.weekly().get(0).goalInt() == 15 && Missions.weekly().get(1).goalInt() == 4
				&& Missions.weekly().get(2).goalInt() == 5 && Missions.weekly().get(3).goalInt() == 500 && Missions.weekly().get(4).goalInt() == 3);
			check("the weekly ones are marked and the daily ones are not", Missions.weekly().stream().allMatch(Missions::isWeekly) && Missions.fixed().stream().noneMatch(Missions::isWeekly));
			check("the week starts on Monday", Missions.weekOf(java.time.LocalDate.of(2031, 5, 11)).equals("2031-05-05") && Missions.weekOf(java.time.LocalDate.of(2031, 5, 12)).equals("2031-05-12")
				&& Missions.weekOf(java.time.LocalDate.of(2031, 5, 5)).equals("2031-05-05"));
			check("explosion chances: 0.8% for a daily mission, 1.5% for a weekly one", Effects.explosionChance == 0.008 && Effects.weeklyExplosionChance == 0.015);
		});
		step("weekly: they fill up over several wins and pay the weekly explosion chance", () -> {
			Effects.fireworkChance = 1;
			Effects.particleChance = 0;
			Effects.explosionChance = 0; // a daily mission never gives an explosion here
			Effects.weeklyExplosionChance = 1; // a weekly one always does
			Missions.setDayForTest(java.time.LocalDate.of(2031, 5, 7));
			List<Missions.Completion> all = new ArrayList<>();
			String[] kits = {"k1", "k2", "k3", "k4", "k1"};
			for (int i = 0; i < 5; i++) {
				Missions.Facts f = new Missions.Facts();
				f.won = true;
				f.kit = kits[i];
				f.hits = 100;
				all.addAll(Missions.record(weekId, "Wendy", f));
			}
			java.util.Set<String> weeklyDone = new java.util.TreeSet<>();
			for (Missions.Completion c : all) if (Missions.isWeekly(c.mission())) weeklyDone.add(c.mission().id());
			check("5 wins in a row with 4 kits and 500 hits complete three weekly missions", weeklyDone.equals(new java.util.TreeSet<>(List.of("w_kits4", "w_run5", "w_hits500"))), weeklyDone.toString());
			check("each one paid an explosion (weekly chance 100%)", Effects.ownedExplosions(weekId).size() == 3 && Missions.weeklyDoneCount(weekId) == 3, Effects.ownedExplosions(weekId).toString());
			check("the daily 'Win 3 duels in a row' is done too", Missions.isDone(weekId, Missions.fixed().get(4)));
			for (int i = 0; i < 10; i++) {
				Missions.Facts f = new Missions.Facts();
				f.won = true;
				f.kit = "k1";
				Missions.record(weekId, "Wendy", f);
			}
			check("the 15th win completes 'Win 15 duels'", Missions.isDone(weekId, Missions.weekly().get(0)) && Missions.weeklyDoneCount(weekId) == 4 && !Missions.isDone(weekId, Missions.weekly().get(4)));
			for (int i = 0; i < 3; i++) {
				Missions.Facts f = new Missions.Facts();
				f.won = true;
				f.team = true;
				f.kit = "k1";
				Missions.record(weekId, "Wendy", f);
			}
			check("three 2v2 wins complete the last one: 5 of 5", Missions.weeklyDoneCount(weekId) == 5 && Missions.isDone(weekId, Missions.weekly().get(4)));
		});
		step("weekly: they last all week, the daily ones do not, and a new week starts from zero", () -> {
			Missions.setDayForTest(java.time.LocalDate.of(2031, 5, 11));
			weekEx[0] = Effects.ownedExplosions(weekId).size();
			check("on Sunday the weekly progress is still there", Missions.weeklyDoneCount(weekId) == 5 && Missions.progress(weekId, Missions.weekly().get(0)) == 15, "" + Missions.progress(weekId, Missions.weekly().get(0)));
			check("but the daily missions of Wednesday are gone", Missions.doneCount(weekId) == 0);
			Missions.setDayForTest(java.time.LocalDate.of(2031, 5, 12));
			check("on the next Monday the week starts again", Missions.weeklyDoneCount(weekId) == 0 && Missions.progress(weekId, Missions.weekly().get(0)) == 0 && !Missions.isDone(weekId, Missions.weekly().get(2)));
			Missions.Facts f = new Missions.Facts();
			f.won = true;
			f.kit = "k9";
			Missions.record(weekId, "Wendy", f);
			check("the first win of the new week counts once", Missions.progress(weekId, Missions.weekly().get(0)) == 1 && Missions.progress(weekId, Missions.weekly().get(2)) == 1
				&& Missions.progress(weekId, Missions.weekly().get(1)) == 1);
			check("and what was won stays (the explosions of the week)", weekEx[0] >= 3 && Effects.ownedExplosions(weekId).size() == weekEx[0], weekEx[0] + " vs " + Effects.ownedExplosions(weekId).size());
			Missions.setDayForTest(null);
		});
		step("midnight: a duel counts for the day it started", () -> {
			Effects.resetForTest();
			Missions.resetForTest();
			Effects.fireworkChance = 1;
			Effects.particleChance = 0;
			Effects.explosionChance = 0;
			Effects.weeklyExplosionChance = 0;
			UUID id = UUID.nameUUIDFromBytes("midnight-player".getBytes());
			Missions.setDayForTest(day1.plusDays(1)); // it is already past midnight when the duel is written down
			Missions.Facts f = new Missions.Facts();
			f.day = day1; // but it started the day before
			f.won = true;
			f.combo = 15;
			List<Missions.Completion> c = Missions.record(id, "Moon", f);
			check("it completes the combo mission of the day it started and pays the reward", c.size() == 1 && c.get(0).mission().id().equals("combo15") && c.get(0).rewards().size() == 1, c.toString());
			check("the new day shows nothing", Missions.doneCount(id) == 0 && Missions.progress(id, Missions.fixed().get(1)) == 0);
			Missions.setDayForTest(day1);
			check("the day it started shows the mission done and the win in the run", Missions.isDone(id, Missions.fixed().get(1)) && Missions.progress(id, Missions.fixed().get(4)) == 1);
			Missions.setDayForTest(day1.plusDays(1));
			Missions.Facts g = new Missions.Facts();
			g.won = true;
			Missions.record(id, "Moon", g);
			check("a duel that starts on the new day counts for the new day", Missions.progress(id, Missions.fixed().get(4)) == 1 && Missions.doneCount(id) == 0);
			Missions.resetForTest();
		});

		// ---------------------------------------------------------------- the finale of a win
		final int[] cine = new int[8];
		final double[] cam = new double[4];
		step("finale: Alice has the Domain Expansion and Carl will watch", () -> {
			Cinema.resetForTest();
			Effects.resetForTest();
			Missions.resetForTest();
			Victory.resetForTest();
			Missions.setDayForTest(day1);
			Effects.grantExplosion(a.getUUID(), "domain_expansion");
			check("Alice equips the Domain Expansion", Effects.setExplosion(a.getUUID(), "domain_expansion") == null && Victory.explosionMode(a.getUUID()));
			for (Dummy p : new Dummy[] {a, b, carl}) p.inbox.clear();
			invite(a, b, new DuelConfig("qkit", false, WinMode.FIRST_TO, 1));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 800);
		step("finale: Carl watches and Alice wins", () -> {
			run(carl, "spectate Alice");
			check("Carl is watching the duel", Spectators.isIn(carl.getUUID()));
			cine[0] = ticks;
			cine[3] = StatsStore.get(a.getUUID()).streak;
			hit(a, b);
		}, () -> Cinema.scenesStarted() >= 1, 200);
		step("finale: it is a Domain Expansion, with a camera and a double of each player", () -> {
			Duel d = duel(a);
			Cinema.Scene scene = Cinema.sceneOf(d);
			check("a finale of the Domain Expansion kind for Alice", scene != null && scene.kind == Cinema.Kind.DOMAIN && scene.win == 0);
			if (scene == null) return;
			check("a camera exists", scene.camera != null && scene.camera.isAlive() && scene.camera instanceof net.minecraft.world.entity.Display);
			check("each player has a double (a mannequin) where they started", scene.doubles[0] instanceof net.minecraft.world.entity.decoration.Mannequin && scene.doubles[1] instanceof net.minecraft.world.entity.decoration.Mannequin
				&& scene.doubles[0].isAlive() && scene.doubles[1].isAlive() && scene.doubles[0].position().distanceTo(scene.stage[0]) < 0.3 && scene.doubles[1].position().distanceTo(scene.stage[1]) < 0.3,
				scene.doubles[0].position() + " " + scene.stage[0]);
			check("the doubles face the middle", Math.abs(scene.doubles[0].getYRot() - scene.yaw[0]) < 1 && Math.abs(scene.doubles[1].getYRot() - scene.yaw[1]) < 1);
			check("both doubles are tagged so they can be found and removed", taggedCount("pvpduels_double") == 2 && taggedCount("pvpduels_camera") == 1, taggedCount("pvpduels_double") + " / " + taggedCount("pvpduels_camera"));
			check("the duel waits for the finale before it closes", d.phase == Duel.Phase.ENDED);
			check("Alice saw VICTORY and Bob saw DEFEAT first, as always", titleHeard(a, "VICTORY") && titleHeard(b, "DEFEAT"));
		}, () -> a.getCamera() != a && b.getCamera() != b, 100);
		step("finale: everybody looks through the camera, the winner's hands are joined", () -> {
			Cinema.Scene scene = Cinema.sceneOf(duel(a));
			check("Alice and Bob see through the camera entity", a.getCamera() == scene.camera && b.getCamera() == scene.camera);
			check("and so does Carl, who is watching", carl.getCamera() == scene.camera);
			check("all in spectator mode, so nothing can touch them", a.gameMode() == net.minecraft.world.level.GameType.SPECTATOR && b.gameMode() == net.minecraft.world.level.GameType.SPECTATOR
				&& carl.gameMode() == net.minecraft.world.level.GameType.SPECTATOR);
			check("the camera was given to three viewers", Cinema.cameraSets() == 3, "" + Cinema.cameraSets());
			check("the game was told to switch the camera", packetCount(a, "ClientboundSetCameraPacket") >= 1 && packetCount(b, "ClientboundSetCameraPacket") >= 1);
			check("the winner's double has the hands joined (the crouching pose), the loser's stands", scene.doubles[0].getPose() == net.minecraft.world.entity.Pose.CROUCHING
				&& scene.doubles[1].getPose() == net.minecraft.world.entity.Pose.STANDING);
			check("the camera starts at the floor in the middle of the arena", scene.cameraAt != null && Math.abs(scene.cameraAt.x - scene.center.x) < 1.5 && Math.abs(scene.cameraAt.z - scene.center.z) < 1.5
				&& scene.cameraAt.y < scene.center.y + 1.5, String.valueOf(scene.cameraAt));
			check("the viewers' bodies are where the camera is", a.position().distanceTo(scene.cameraAt) < 2.5 && b.position().distanceTo(scene.cameraAt) < 2.5, a.position() + " " + scene.cameraAt);
			check("Alice's fireworks do not go up: she has an explosion, not effects", Victory.fireworkLaunches() == 0);
			cam[0] = scene.cameraAt.y;
		}, () -> titleHeard(a, "Domain Expansion: I win!") && titleHeard(b, "Domain Expansion: I win!"), 200);
		step("finale: the title reached everybody and the hands come apart", () -> {
			Cinema.Scene scene = Cinema.sceneOf(duel(a));
			check("the title 'Domain Expansion: I win!' reached Alice, Bob and Carl", titleHeard(a, "Domain Expansion: I win!") && titleHeard(b, "Domain Expansion: I win!") && titleHeard(carl, "Domain Expansion: I win!"));
			check("1.5 seconds passed with the hands joined", scene.doubles[0].getPose() != net.minecraft.world.entity.Pose.CROUCHING || scene.doubles[0].getPose() == net.minecraft.world.entity.Pose.STANDING);
			check("the camera has risen a little by now", scene.cameraAt.y > cam[0] + 0.5, scene.cameraAt.y + " vs " + cam[0]);
			check("the dome is being drawn (many particles reach the players)", particlePackets(a) > 150 && particlePackets(b) > 150, particlePackets(a) + " / " + particlePackets(b));
		}, () -> Cinema.sceneOf(duel(a)) != null && Cinema.sceneOf(duel(a)).cameraAt.y > Cinema.sceneOf(duel(a)).center.y + 6.9, 400);
		step("finale: the camera ends 7 blocks up, to the side and toward the winner's end", () -> {
			Cinema.Scene scene = Cinema.sceneOf(duel(a));
			double rise = scene.cameraAt.y - scene.center.y;
			check("it rose about 7 blocks", rise > 6.9 && rise < 7.8, "" + rise);
			check("it curved to the side of the arena", Math.abs(scene.cameraAt.z - scene.center.z) > scene.half * 0.8, scene.cameraAt + " half " + scene.half);
			check("and bent toward the winner's end (Alice started on the west side)", scene.cameraAt.x < scene.center.x - scene.half * 0.3, scene.cameraAt + " center " + scene.center);
			check("it looks down at the arena", scene.cameraPitch > 10, "" + scene.cameraPitch);
			check("the camera sent many position packets", Cinema.cameraMoves() > 100, "" + Cinema.cameraMoves());
			check("the double of the winner stands again", scene.doubles[0].getPose() == net.minecraft.world.entity.Pose.STANDING);
		}, () -> true, 1);
		step("finale: the duel closes and everything is put back", () -> {
			cine[2] = ticks;
		}, () -> noDuel(a) && noDuel(b), 800);
		step("finale: after it", () -> {
			check("the finale lasted longer than a normal end (more than 8 seconds from the win)", cine[2] - cine[0] >= 0 && ticks - cine[0] >= 170, (ticks - cine[0]) + " ticks");
			check("nobody is looking through the camera any more", a.getCamera() == a && b.getCamera() == b && carl.getCamera() == carl);
			check("nobody is left in spectator mode (Carl stopped watching too)", a.gameMode() != net.minecraft.world.level.GameType.SPECTATOR && b.gameMode() != net.minecraft.world.level.GameType.SPECTATOR
				&& carl.gameMode() != net.minecraft.world.level.GameType.SPECTATOR && !Spectators.isIn(carl.getUUID()));
			check("the camera and the doubles are gone", Cinema.activeScenes() == 0 && taggedCount("pvpduels_double") == 0 && taggedCount("pvpduels_camera") == 0,
				Cinema.activeScenes() + " / " + taggedCount("pvpduels_double") + " / " + taggedCount("pvpduels_camera"));
			check("she won, so her streak went up", StatsStore.get(a.getUUID()).streak == cine[3] + 1, StatsStore.get(a.getUUID()).streak + " vs " + cine[3]);
		});

		// ---------------------------------------------------------------- ties
		step("tie: both with normal effects, nothing happens", () -> {
			Effects.setExplosion(a.getUUID(), "");
			cine[0] = Cinema.scenesStarted();
			cine[1] = StatsStore.get(b.getUUID()).draws;
			cine[3] = StatsStore.get(a.getUUID()).streak;
			check("Alice has a win streak before the tie", cine[3] >= 1, "" + cine[3]);
			invite(a, b, new DuelConfig("qkit", false, WinMode.FIRST_TO, 1));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 800);
		step("tie: the duel just ends, with no finale", () -> {
			Duel d = duel(a);
			d.finishAsTieForTest();
			check("no finale for a tie of normal effects", Cinema.sceneOf(d) == null && Cinema.scenesStarted() == cine[0]);
			check("the DRAW title, nothing else", titleHeard(a, "DRAW") && !titleHeard(a, "Domain Expansion!") && !titleHeard(a, "I guess it's a tie"));
			check("it is a draw in the stats and it broke Alice's win streak", StatsStore.get(b.getUUID()).draws == cine[1] + 1 && StatsStore.get(a.getUUID()).streak == 0,
				StatsStore.get(a.getUUID()).streak + " / " + StatsStore.get(b.getUUID()).draws);
			check("the players stay in their bodies", a.getCamera() == a && b.getCamera() == b && taggedCount("pvpduels_double") == 0);
		}, () -> noDuel(a) && noDuel(b), 600);
		step("tie: one Domain Expansion and one normal player, nothing happens either", () -> {
			Effects.setExplosion(a.getUUID(), "domain_expansion");
			Effects.setExplosion(b.getUUID(), "");
			cine[0] = Cinema.scenesStarted();
			invite(a, b, new DuelConfig("qkit", false, WinMode.FIRST_TO, 1));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 800);
		step("tie: the duel just ends", () -> {
			Duel d = duel(a);
			d.finishAsTieForTest();
			check("no finale, no domain", Cinema.sceneOf(d) == null && Cinema.scenesStarted() == cine[0] && !titleHeard(a, "Domain Expansion!") && !titleHeard(b, "Domain Expansion!"));
			check("nobody was moved", a.getCamera() == a && b.getCamera() == b && a.gameMode() != net.minecraft.world.level.GameType.SPECTATOR);
		}, () -> noDuel(a) && noDuel(b), 600);
		step("tie: two Domain Expansions clash", () -> {
			Effects.grantExplosion(b.getUUID(), "domain_expansion");
			check("Bob equips the Domain Expansion too", Effects.setExplosion(b.getUUID(), "domain_expansion") == null);
			cine[0] = Cinema.scenesStarted();
			cine[1] = Cinema.cameraSets();
			for (Dummy p : new Dummy[] {a, b}) p.inbox.clear();
			invite(a, b, new DuelConfig("qkit", false, WinMode.FIRST_TO, 1));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 800);
		step("tie: the clash starts", () -> {
			cine[2] = ticks;
			Duel d = duel(a);
			d.finishAsTieForTest();
			Cinema.Scene scene = Cinema.sceneOf(d);
			check("a clash finale starts", scene != null && scene.kind == Cinema.Kind.CLASH && scene.win == -1 && Cinema.scenesStarted() == cine[0] + 1);
		}, () -> a.getCamera() != a && b.getCamera() != b, 100);
		step("tie: both domains grow and meet", () -> {
			Cinema.Scene scene = Cinema.sceneOf(duel(a));
			check("both players ride the camera", Cinema.cameraSets() == cine[1] + 2 && a.getCamera() == scene.camera && b.getCamera() == scene.camera);
			check("both doubles join their hands", scene.doubles[0].getPose() == net.minecraft.world.entity.Pose.CROUCHING && scene.doubles[1].getPose() == net.minecraft.world.entity.Pose.CROUCHING);
		}, () -> titleHeard(a, "Domain Expansion!") && titleHeard(b, "Domain Expansion!"), 200);
		step("tie: the domains push each other", () -> {
			check("the two domains are drawn (the red one, the blue one and the dark gray line)", particlePackets(a) > 50 && particlePackets(b) > 50, particlePackets(a) + " / " + particlePackets(b));
			Cinema.Scene scene = Cinema.sceneOf(duel(a));
			check("the doubles stand again", scene.doubles[0].getPose() == net.minecraft.world.entity.Pose.STANDING && scene.doubles[1].getPose() == net.minecraft.world.entity.Pose.STANDING);
		}, () -> titleHeard(a, "I guess it's a tie") && titleHeard(b, "I guess it's a tie"), 500);
		step("tie: they get tired and the title says it is a tie", () -> {
			check("both heard 'I guess it's a tie'", titleHeard(a, "I guess it's a tie") && titleHeard(b, "I guess it's a tie"));
			check("the clash was longer than 9 seconds", ticks - cine[2] >= 180, (ticks - cine[2]) + " ticks");
		}, () -> noDuel(a) && noDuel(b), 600);
		step("tie: everything is put back", () -> {
			check("nobody looks through the camera and nobody is in spectator mode", a.getCamera() == a && b.getCamera() == b && a.gameMode() != net.minecraft.world.level.GameType.SPECTATOR
				&& b.gameMode() != net.minecraft.world.level.GameType.SPECTATOR);
			check("no camera and no doubles are left", Cinema.activeScenes() == 0 && taggedCount("pvpduels_double") == 0 && taggedCount("pvpduels_camera") == 0);
		});

		// ---------------------------------------------------------------- a normal win with fireworks, and somebody leaving during the finale
		step("finale: Bob wins with three fireworks (the duel started before midnight)", () -> {
			Effects.setExplosion(a.getUUID(), "");
			Effects.setExplosion(b.getUUID(), "");
			UUID bob = b.getUUID();
			Effects.grantItem(bob, "color:red");
			Effects.grantItem(bob, "color:blue");
			Effects.toggleColor(bob, 0, "white"); // the Domain Expansion of the clash took his white off
			check("Bob puts red and blue on the left and right fireworks", Effects.toggleColor(bob, 1, "red") == null && Effects.toggleColor(bob, 2, "blue") == null && Effects.fireworkColors(bob, 0).equals(List.of("white")));
			Victory.resetForTest();
			cine[4] = Cinema.scenesStarted();
			Missions.setDayForTest(day1); // the duel starts "yesterday"
			invite(a, b, new DuelConfig("qkit", false, WinMode.FIRST_TO, 1));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 800);
		step("finale: midnight passes and Bob wins", () -> {
			Missions.setDayForTest(day1.plusDays(1));
			hit(b, a);
		}, () -> Cinema.scenesStarted() > cine[4] && Victory.fireworkLaunches() == 3, 300);
		step("finale: the three fireworks stand in a row behind Bob, the middle one a block higher", () -> {
			Cinema.Scene scene = Cinema.sceneOf(duel(a));
			check("a normal finale for Bob", scene != null && scene.kind == Cinema.Kind.NORMAL && scene.win == 1);
			List<net.minecraft.world.entity.projectile.FireworkRocketEntity> rockets = new ArrayList<>();
			for (net.minecraft.world.entity.Entity e : Arena.level(server).getAllEntities()) {
				if (e instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity r && r.position().distanceTo(scene.stage[1]) < 12) rockets.add(r);
			}
			check("three rockets are flying near Bob's place", rockets.size() == 3, "" + rockets.size());
			boolean behind = true;
			double minZ = 1e9, maxZ = -1e9, minY = 1e9, maxY = -1e9;
			for (var r : rockets) {
				if (r.getX() < scene.stage[1].x + 1.5) behind = false; // Bob stands east and looks west: behind him is further east
				minZ = Math.min(minZ, r.getZ());
				maxZ = Math.max(maxZ, r.getZ());
				minY = Math.min(minY, r.getY());
				maxY = Math.max(maxY, r.getY());
			}
			check("all of them are behind him", behind);
			check("in a row: one in front of him and two 2.5 blocks to each side", Math.abs((maxZ - minZ) - 5.0) < 1.0, "" + (maxZ - minZ));
			check("the middle one started a block higher", Math.abs((maxY - minY) - 1.0) < 0.6, "" + (maxY - minY));
			check("Alice did not get fireworks and Bob's explosion slot is empty, so only fireworks", Victory.fireworkLaunches() == 3 && Effects.explosionSlot(b.getUUID()).isEmpty());
		}, () -> a.getCamera() != a && b.getCamera() != b, 100);
		step("finale: Alice leaves during the finale", () -> {
			run(a, "leave");
			check("Alice has her own eyes back at once", a.getCamera() == a && a.gameMode() != net.minecraft.world.level.GameType.SPECTATOR);
			check("Bob still watches the show", b.getCamera() != b && Cinema.activeScenes() == 1);
		}, () -> noDuel(b), 800);
		step("finale: Bob's duel counted for the day it started", () -> {
			check("Bob is back in his own eyes", b.getCamera() == b && b.gameMode() != net.minecraft.world.level.GameType.SPECTATOR);
			check("no finale is left", Cinema.activeScenes() == 0 && taggedCount("pvpduels_double") == 0 && taggedCount("pvpduels_camera") == 0);
			Missions.setDayForTest(day1.plusDays(1));
			check("on the day after, Bob's win run is empty", Missions.progress(b.getUUID(), Missions.fixed().get(4)) == 0);
			Missions.setDayForTest(day1);
			check("on the day the duel started, it counts as his first win", Missions.progress(b.getUUID(), Missions.fixed().get(4)) == 1, "" + Missions.progress(b.getUUID(), Missions.fixed().get(4)));
		});
		step("finale: clean up", () -> {
			Cinema.resetForTest();
			Cinema.enabled = false;
			Effects.resetForTest();
			Missions.resetForTest();
			Victory.resetForTest();
			check("everything is reset and the older tests see the plain end of a duel again", Cinema.activeScenes() == 0 && !Cinema.enabled);
		});

		// ---------------------------------------------------------------- delivery 8: unlock_all, quality, explosions, test bot, replays, draws
		final String[] rid = new String[1];
		final java.nio.file.Path[] rdir = new java.nio.file.Path[1];
		final int[] d8 = new int[10];
		final double[] rp = new double[4];
		final int[] sums = new int[5];
		step("delivery 9: the mod is not on its first version any more", () -> {
			String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("pvpduels").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
			check("the mod says 0.9.0", version.equals("0.9.0"), version);
		});
		step("/unlock_all: only admins with the right code", () -> {
			UUID id = dora.getUUID();
			dora.inbox.clear();
			check("Dora starts with nothing", Effects.ownedExplosions(id).isEmpty() && Effects.ownedParticles(id).isEmpty());
			run(dora, "unlock_all gustavo_matalo!");
			check("a normal player can't use /unlock_all, not even with the right code", Effects.ownedExplosions(id).isEmpty() && Effects.ownedParticles(id).isEmpty());
			try {
				com.pvpduels.effects.EffectCommands.unlockWithCode(dora.createCommandSourceStack(), "wrong_code");
				check("a wrong code unlocks nothing and says so", dora.heard("Wrong code") && Effects.ownedExplosions(id).isEmpty() && Effects.ownedParticles(id).isEmpty());
				com.pvpduels.effects.EffectCommands.unlockWithCode(dora.createCommandSourceStack(), "gustavo_matalo");
				check("the code without the ! is wrong too", Effects.ownedExplosions(id).isEmpty());
				com.pvpduels.effects.EffectCommands.unlockWithCode(dora.createCommandSourceStack(), "gustavo_matalo!");
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
				throw new RuntimeException(e);
			}
			check("the right code unlocks every cosmetic", Effects.ownedExplosions(id).size() == Effects.EXPLOSIONS.length && Effects.ownedParticles(id).size() == Particles.all().size()
				&& Effects.ownedItemCount(id) == 23, Effects.ownedExplosions(id).size() + " / " + Effects.ownedItemCount(id));
			check("and she is told", dora.heard("Everything is unlocked"));
		});
		step("quality: five levels and a click to change", () -> {
			com.pvpduels.effects.Quality[] all = com.pvpduels.effects.Quality.values();
			check("Potato, Low, Mid, High and Ultra Pro Max", all.length == 5 && all[0].label.equals("Potato") && all[1].label.equals("Low") && all[2].label.equals("Mid")
				&& all[3].label.equals("High") && all[4].label.equals("Ultra Pro Max"));
			check("a new player is on High", Effects.quality(a.getUUID()) == com.pvpduels.effects.Quality.HIGH);
			run(a, "effects");
			check("the main menu shows the level", menuHas(a, "Effect quality: High"));
			menuStep(a, "Effect quality: High");
			check("one click goes up to Ultra Pro Max", Effects.quality(a.getUUID()) == com.pvpduels.effects.Quality.ULTRA && menuHas(a, "Effect quality: Ultra Pro Max"));
			menuStep(a, "Effect quality: Ultra Pro Max");
			check("then it starts again at Potato", Effects.quality(a.getUUID()) == com.pvpduels.effects.Quality.POTATO && menuHas(a, "Effect quality: Potato"));
			menuStep(a, "Effect quality: Potato");
			menuStep(a, "Effect quality: Low");
			menuStep(a, "Effect quality: Mid");
			check("Low, Mid and High again", Effects.quality(a.getUUID()) == com.pvpduels.effects.Quality.HIGH);
			a.closeContainer();
		});
		step("quality: the number of particles a player receives", () -> {
			net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) b.level();
			for (com.pvpduels.effects.Quality q : com.pvpduels.effects.Quality.values()) {
				Effects.setQuality(b.getUUID(), q);
				b.link.sent.clear();
				for (int i = 0; i < 20; i++) com.pvpduels.effects.Quality.send(level, b, net.minecraft.core.particles.ParticleTypes.FLAME, b.getX(), b.getY() + 1, b.getZ(), 100, 0, 0, 0, 0);
				int sum = 0;
				for (Packet<?> packet : List.copyOf(b.link.sent)) if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket pk) sum += pk.getCount();
				sums[q.ordinal()] = sum;
			}
			Effects.setQuality(b.getUUID(), com.pvpduels.effects.Quality.HIGH);
			check("High sends all of them", sums[3] == 2000, "" + sums[3]);
			check("Ultra Pro Max sends twice as many", sums[4] == 4000, "" + sums[4]);
			check("Potato about a tenth", sums[0] >= 180 && sums[0] <= 220, "" + sums[0]);
			check("Low about a third", sums[1] >= 650 && sums[1] <= 750, "" + sums[1]);
			check("Mid about two thirds", sums[2] >= 1250 && sums[2] <= 1350, "" + sums[2]);
		});
		final int[] fx = new int[4];
		for (String ex : new String[] {"orbital_strike", "orbital_wither", "shooting_star", "black_hole", "meteor_shower"}) {
			step("explosions: " + ex + " plays", () -> {
				Effects.grantExplosion(a.getUUID(), ex);
				check("Alice equips it", Effects.setExplosion(a.getUUID(), ex) == null && Effects.explosionSlot(a.getUUID()).equals(ex) && Victory.explosionMode(a.getUUID()));
				a.link.sent.clear();
				d8[4] = Victory.showsStarted();
				fx[0] = ticks;
				fx[1] = com.pvpduels.effects.Props.createdCount();
				fx[2] = com.pvpduels.effects.Props.burstCount();
				Victory.preview(a);
				check("a show started", Victory.showsStarted() == d8[4] + 1 && Victory.activeShows() >= 1);
			}, () -> ticks >= fx[0] + 75, 300);
			step("explosions: " + ex + " has its solid parts in the middle of the show", () -> {
				check("the show is still on", Victory.activeShows() == 1);
				check("display entities were made (" + ex + ")", com.pvpduels.effects.Props.createdCount() >= fx[1] + 3, (com.pvpduels.effects.Props.createdCount() - fx[1]) + " made");
				check("and they are in the world", com.pvpduels.effects.Props.live() > 0 && com.pvpduels.effects.Props.tagged(server) > 0, com.pvpduels.effects.Props.live() + " / " + com.pvpduels.effects.Props.tagged(server));
			}, () -> Victory.activeShows() == 0, 800);
			step("explosions: " + ex + " reached Alice and left nothing behind", () -> {
				int sum = 0;
				int packets = 0;
				for (Packet<?> packet : List.copyOf(a.link.sent)) {
					if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket pk) {
						sum += pk.getCount();
						packets++;
					}
				}
				check("a lot of particles reached her (" + ex + ")", sum > 150 && packets > 150, sum + " in " + packets + " packets");
				check("the show ended by itself", Victory.activeShows() == 0);
				check("no display entity is left", com.pvpduels.effects.Props.live() == 0 && com.pvpduels.effects.Props.tagged(server) == 0, com.pvpduels.effects.Props.live() + " / " + com.pvpduels.effects.Props.tagged(server));
			});
		}
		step("explosions: fireworks burst in the sky of the big ones", () -> {
			check("the shows launched real firework bursts", com.pvpduels.effects.Props.burstCount() >= 12, String.valueOf(com.pvpduels.effects.Props.burstCount()));
			check("the sweep finds nothing to clean", com.pvpduels.effects.Props.sweep(server) == 0);
		});
		step("explosions: back to normal", () -> {
			Effects.setExplosion(a.getUUID(), "");
			check("Alice has no explosion equipped", Effects.explosionSlot(a.getUUID()).isEmpty());
		});

		// the test bot
		step("test bot: the command is for admins and the bot has a level", () -> {
			try {
				rdir[0] = Files.createTempDirectory("pvpduels-replays");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			ReplayStore.reloadForTest(rdir[0]);
			run(a, "testbot");
			check("a normal player can't use /testbot", server.getPlayerList().getPlayerByName("TestBot") == null);
			check("the first free name is TestBot", TestDummy.freeName(server).equals("TestBot"));
			String problem = TestDummy.spawn(a, TestDummy.freeName(server), BotLevel.STATIC);
			Dummy bot = (Dummy) server.getPlayerList().getPlayerByName("TestBot");
			check("the bot was created next to Alice", problem == null && bot != null && a.distanceTo(bot) < 5, String.valueOf(problem));
			check("it remembers its level and accepts duels", bot != null && TestDummy.levelOf(bot.getUUID()) == BotLevel.STATIC && TestDummy.accepts(bot.getUUID()));
			check("the next free name is TestBot2", TestDummy.freeName(server).equals("TestBot2"));
			check("a second bot with another level", TestDummy.spawn(a, "Easy1", BotLevel.EASY) == null);
			Dummy easy = (Dummy) server.getPlayerList().getPlayerByName("Easy1");
			check("it has the level Easy", easy != null && TestDummy.levelOf(easy.getUUID()) == BotLevel.EASY);
			check("/testbot remove takes it away", TestDummy.remove(server, "Easy1") == 1 && server.getPlayerList().getPlayerByName("Easy1") == null && TestDummy.levelOf(easy.getUUID()) == null);
		});
		step("test bot: Alice challenges it", () -> {
			Dummy bot = (Dummy) server.getPlayerList().getPlayerByName("TestBot");
			DuelManager.sendInvite(a, bot, a.getUUID(), new DuelConfig("qkit", false, WinMode.FIRST_TO, 1), false);
			check("the bot accepted by itself", duel(a) != null && duel(bot) == duel(a));
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("test bot: the fight goes on for a while (it is being recorded)", () -> {
			d8[0] = ticks;
		}, () -> ticks >= d8[0] + 70, 300);
		step("test bot: Alice kills it", () -> {
			Dummy bot = (Dummy) server.getPlayerList().getPlayerByName("TestBot");
			hit(a, bot);
			check("first to 1: Alice wins", duel(a).winner == 0 && duel(a).points[0] == 1);
		}, () -> noDuel(a), 600);
		step("replay: the duel was recorded and kept", () -> {
			check("one replay is in the store", ReplayStore.count() == 1, "" + ReplayStore.count());
			ReplayStore.Entry e = ReplayStore.recent(null, 1).get(0);
			rid[0] = e.id;
			check("with the players, the score and the winner", e.names[0].equals("Alice") && e.names[1].equals("TestBot") && e.winner == 0 && e.points[0] == 1 && e.points[1] == 0,
				e.names[0] + " " + e.names[1] + " " + e.winner);
			check("it lasted a few seconds and the file is on disk", e.seconds >= 3 && e.bytes > 0 && Files.exists(ReplayStore.fileOf(e.id)), e.seconds + " s " + e.bytes + " bytes");
			try {
				ReplayData data = ReplayStore.load(e.id);
				check("the file reads back with a picture of every tick", data.frames >= 70 && data.frames == data.x[0].length || data.frames >= 70, "" + data.frames);
				check("with the kit and the rounds", data.config.kit().equals("qkit") && !data.events.isEmpty() && data.events.get(0).type() == ReplayData.EV_ROUND_START);
				check("the two players start on different sides", Math.abs(data.x[0][0] - data.x[1][0]) > 3, data.x[0][0] + " / " + data.x[1][0]);
				boolean moved = false;
				for (int f = 1; f < data.frames; f++) if (Math.abs(data.x[0][f] - data.x[0][0]) > 0.05 || Math.abs(data.z[0][f] - data.z[0][0]) > 0.05) moved = true;
				check("the recording is not just one picture repeated (Alice moved or turned)", moved || data.frames > 100);
			} catch (IOException ex) {
				check("the file can be read", false, ex.toString());
			}
		});
		step("replay: the list, asking an admin and the answers", () -> {
			for (Dummy p : new Dummy[] {a, b}) p.inbox.clear();
			run(a, "replay");
			check("Alice sees her replay with a button to ask an admin", a.heard("Your replays") && a.heard("#" + rid[0]) && a.heard("[ASK ADMIN]"));
			run(b, "replay");
			check("Bob took no part: his list is empty", b.heard("Nothing recorded yet") && !b.heard("#" + rid[0]));
			run(a, "replay watch " + rid[0]);
			check("without permission she can't watch it", a.heard("An admin has to send you that replay first") && !ReplayPlayer.isIn(a.getUUID()));
			run(b, "replay ask " + rid[0]);
			check("Bob can't ask for a duel he did not play", b.heard("didn't take part") && ReplayStore.pending().isEmpty());
			run(a, "replay ask");
			check("Alice asks for her latest replay", a.heard("Request sent for #" + rid[0]) && ReplayStore.pending().size() == 1);
			run(a, "replay ask");
			check("asking twice does not make a second request", a.heard("already waiting") && ReplayStore.pending().size() == 1);
			ReplayStore.reloadForTest(rdir[0]);
			check("the request is in a file: it survives a restart", ReplayStore.count() == 1 && ReplayStore.pending().size() == 1 && ReplayStore.pending().get(0).name.equals("Alice")
				&& ReplayStore.pending().get(0).replay.equals(rid[0]));
			a.inbox.clear();
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "replay deny " + rid[0] + " Alice");
			check("an admin says no: Alice is told and nothing is waiting", a.heard("said no to the replay") && ReplayStore.pending().isEmpty()
				&& !ReplayStore.mayWatch(ReplayStore.get(rid[0]), a.getUUID(), false));
			run(a, "replay ask " + rid[0]);
			check("after a no she can ask again", ReplayStore.pending().size() == 1);
			ReplayStore.answer(ReplayStore.pending().get(0), "denied");
			check("an answer given while she is away waits for her", ReplayStore.untold(a.getUUID()).size() == 1);
			a.inbox.clear();
			ReplayCommands.tellNow(a);
			check("she hears it when she is back, only once", a.heard("said no to the replay") && ReplayStore.untold(a.getUUID()).isEmpty());
			run(a, "replay ask " + rid[0]);
			a.inbox.clear();
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "replay send " + rid[0] + " Alice");
			check("an admin sends it: Alice is told with a button and can watch", a.heard("An admin sent you the replay") && a.heard("[WATCH]") && ReplayStore.pending().isEmpty()
				&& ReplayStore.mayWatch(ReplayStore.get(rid[0]), a.getUUID(), false));
			check("Bob still can't", !ReplayStore.mayWatch(ReplayStore.get(rid[0]), b.getUUID(), false));
		});
		step("replay: Alice watches it", () -> {
			a.inbox.clear();
			a.link.sent.clear();
			d8[5] = ReplayPlayer.startedCount();
			run(a, "replay watch " + rid[0]);
			check("the replay started", ReplayPlayer.isIn(a.getUUID()) && ReplayPlayer.startedCount() == d8[5] + 1 && ReplayPlayer.sessions() == 1);
			check("she is a spectator and her inventory is saved", a.gameMode() == GameType.SPECTATOR && PlayerBackup.exists(a.getUUID()));
			check("it takes an arena, like a duel", DuelManager.activeDuels() == 1 && DuelManager.isBusy(a.getUUID()));
			check("she was told the controls", a.heard("Replay: Alice vs TestBot") && a.heard("[PAUSE]") && a.heard("[RESTART]") && a.heard("[STOP]"));
		}, () -> ReplayPlayer.of(a.getUUID()) != null && a.getCamera() != a, 200);
		step("replay: it plays", () -> {
			ReplayPlayer s = ReplayPlayer.of(a.getUUID());
			check("she looks through the camera", a.getCamera() != a);
			check("a double of each player and a camera exist", taggedCount("pvpduels_replay_dbl") == 2 && taggedCount("pvpduels_replay_cam") == 1, taggedCount("pvpduels_replay_dbl") + " / " + taggedCount("pvpduels_replay_cam"));
			check("normal speed, not paused", s.speed == 1.0 && !s.paused);
			rp[0] = s.pos;
			d8[0] = ticks;
		}, () -> ticks >= d8[0] + 12, 100);
		step("replay: pause", () -> {
			ReplayPlayer s = ReplayPlayer.of(a.getUUID());
			check("the picture moved on", s.pos > rp[0] + 5, rp[0] + " -> " + s.pos);
			boolean hud = false;
			for (Packet<?> packet : List.copyOf(a.link.sent)) {
				if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat && chat.overlay() && chat.content().getString().contains("REPLAY")) hud = true;
			}
			check("the heads-up line (vida, tiempo, velocidad) is being sent", hud);
			run(a, "replay pause");
			check("paused", s.paused);
			rp[1] = s.pos;
			d8[0] = ticks;
		}, () -> ticks >= d8[0] + 10, 100);
		step("replay: speed, view and restart", () -> {
			ReplayPlayer s = ReplayPlayer.of(a.getUUID());
			check("while paused the picture stays", s.pos == rp[1], rp[1] + " -> " + s.pos);
			run(a, "replay speed 2");
			check("a speed makes it play again", s.speed == 2.0 && !s.paused);
			run(a, "replay view");
			check("the view changes", s.view == 1);
			run(a, "replay restart");
			check("restart goes back to the start", s.pos < 1.0, "" + s.pos);
			d8[0] = ticks;
		}, () -> ticks >= d8[0] + 10, 100);
		step("replay: twice as fast", () -> {
			ReplayPlayer s = ReplayPlayer.of(a.getUUID());
			check("about 20 pictures in 10 ticks", s.pos > 14 && s.pos < 26, "" + s.pos);
			run(b, "replay pause");
			check("somebody who is not watching gets an error", b.heard("You are not watching a replay"));
			run(a, "leave");
			check("/leave stops the replay", !ReplayPlayer.isIn(a.getUUID()));
			check("Alice has her own eyes and mode back", a.getCamera() == a && a.gameMode() != GameType.SPECTATOR);
		}, () -> true, 1);
		step("replay: after /leave nothing is left", () -> {
			check("her inventory was given back, the camera and the doubles are gone, the arena is free", !PlayerBackup.exists(a.getUUID()) && taggedCount("pvpduels_replay") == 0 && DuelManager.activeDuels() == 0,
				PlayerBackup.exists(a.getUUID()) + " " + taggedCount("pvpduels_replay") + " " + DuelManager.activeDuels());
		});
		step("replay: to the end at 8x", () -> {
			a.link.sent.clear();
			run(a, "replay watch " + rid[0]);
			run(a, "replay speed 8");
			check("watching again works", ReplayPlayer.isIn(a.getUUID()));
		}, () -> !ReplayPlayer.isIn(a.getUUID()), 700);
		step("replay: it ended by itself and put everything back", () -> {
			check("the title named the winner", titleHeard(a, "Alice WINS"));
			check("Alice is back to normal", a.getCamera() == a && a.gameMode() != GameType.SPECTATOR && !PlayerBackup.exists(a.getUUID()));
			check("nothing is left", taggedCount("pvpduels_replay") == 0 && DuelManager.activeDuels() == 0 && ReplayPlayer.sessions() == 0);
		});
		step("replay: an admin watches without asking", () -> {
			ReplayCommands.watch(b, rid[0], false);
			check("a player with no permission can't", !ReplayPlayer.isIn(b.getUUID()));
			ReplayCommands.watch(carl, rid[0], true);
			check("an admin can", ReplayPlayer.isIn(carl.getUUID()));
			ReplayCommands.watch(dora, rid[0], true);
			check("two people can watch at the same time", ReplayPlayer.sessions() == 2 && DuelManager.activeDuels() == 2, ReplayPlayer.sessions() + " / " + DuelManager.activeDuels());
			run(carl, "leave");
			run(dora, "leave");
			check("both leave with /leave", ReplayPlayer.sessions() == 0 && !PlayerBackup.exists(carl.getUUID()) && !PlayerBackup.exists(dora.getUUID()));
		}, () -> true, 1);
		step("replay: the arenas are free again", () -> {
			check("no camera, no doubles, no arena taken", DuelManager.activeDuels() == 0 && taggedCount("pvpduels_replay") == 0, DuelManager.activeDuels() + " " + taggedCount("pvpduels_replay"));
		});
		step("replay: delete", () -> {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "replay delete " + rid[0]);
			check("it is gone from the store and from the disk", ReplayStore.count() == 0 && !Files.exists(ReplayStore.fileOf(rid[0])));
			a.inbox.clear();
			run(a, "replay watch " + rid[0]);
			check("and it can't be watched", a.heard("There is no replay") && !ReplayPlayer.isIn(a.getUUID()));
		});

		// /draw
		step("draw: the test bot accepts a draw right away", () -> {
			Dummy bot = (Dummy) server.getPlayerList().getPlayerByName("TestBot");
			d8[2] = StatsStore.get(a.getUUID()).draws;
			DuelManager.sendInvite(a, bot, a.getUUID(), new DuelConfig("qkit", false, WinMode.FIRST_TO, 3), false);
			check("a duel with the bot", duel(a) != null);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("draw: Alice offers", () -> {
			a.inbox.clear();
			run(a, "draw");
			check("the bot agreed and the duel is a tie", a.heard("Draw agreed") && duel(a) != null && duel(a).winner == -1 && duel(a).phase == Duel.Phase.ENDED,
				duel(a) == null ? "no duel" : duel(a).phase + " " + duel(a).winner);
		}, () -> noDuel(a), 600);
		step("draw: the replay of a tie says so, and bot duels don't change the stats", () -> {
			check("one replay, a draw", ReplayStore.count() == 1 && ReplayStore.recent(null, 1).get(0).winner == -1);
			check("no draw in the statistics (it was a bot)", StatsStore.get(a.getUUID()).draws == d8[2], StatsStore.get(a.getUUID()).draws + " vs " + d8[2]);
			check("TestBot is removed with /testbot remove", TestDummy.remove(server, null) == 1 && server.getPlayerList().getPlayerByName("TestBot") == null);
			ReplayStore.delete(ReplayStore.recent(null, 1).get(0).id);
		});
		step("draw: Alice and Bob, the countdown first", () -> {
			for (Dummy p : new Dummy[] {a, b}) p.inbox.clear();
			d8[2] = StatsStore.get(a.getUUID()).draws;
			d8[3] = StatsStore.get(b.getUUID()).draws;
			invite(a, b, new DuelConfig("qkit", false, WinMode.FIRST_TO, 3));
			check("a duel started", duel(a) != null);
			if (phaseIs(a, Duel.Phase.STARTING)) {
				run(a, "draw");
				check("during the countdown a draw can't be offered", a.heard("while a round is being fought") && !Draws.hasOffer(duel(a)));
			}
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("draw: an offer, a no, and a second offer", () -> {
			run(b, "draw accept");
			check("nothing to accept yet", b.heard("There is no draw offer to accept"));
			run(a, "draw");
			check("Bob got the offer with the buttons", b.heard("Alice offers a draw") && b.heard("[ACCEPT]") && b.heard("[DENY]") && Draws.hasOffer(duel(a)));
			check("Alice was told it was sent", a.heard("Draw offer sent"));
			a.inbox.clear();
			run(a, "draw");
			check("offering twice only reminds her", a.heard("already offered") && duel(a).phase == Duel.Phase.FIGHTING);
			run(a, "draw accept");
			check("you can't accept your own offer", a.heard("There is no draw offer to accept") && duel(a).phase == Duel.Phase.FIGHTING);
			run(b, "draw deny");
			check("Bob says no: Alice is told and the fight goes on", a.heard("Bob denied the draw") && !Draws.hasOffer(duel(a)) && duel(a).phase == Duel.Phase.FIGHTING);
			run(b, "draw deny");
			check("nobody offered now", b.heard("Nobody offered you a draw"));
			Draws.expires = 5;
			run(a, "draw");
			check("a new offer", Draws.hasOffer(duel(a)));
			d8[0] = ticks;
		}, () -> ticks >= d8[0] + 15, 100);
		step("draw: an offer that was not answered runs out", () -> {
			b.inbox.clear();
			run(b, "draw accept");
			Draws.expires = 20 * 60;
			check("too late: it expired", b.heard("There is no draw offer to accept") && duel(a).phase == Duel.Phase.FIGHTING);
			run(a, "draw");
			a.inbox.clear();
			run(b, "draw accept");
			check("accepted: the duel ends in a tie", a.heard("Draw agreed") && b.heard("Draw agreed") && duel(a).winner == -1 && duel(a).phase == Duel.Phase.ENDED);
		}, () -> noDuel(a) && noDuel(b), 600);
		step("draw: the stats", () -> {
			check("it counts as a draw for both", StatsStore.get(a.getUUID()).draws == d8[2] + 1 && StatsStore.get(b.getUUID()).draws == d8[3] + 1,
				StatsStore.get(a.getUUID()).draws + " / " + StatsStore.get(b.getUUID()).draws);
			check("and breaks the win streak", StatsStore.get(a.getUUID()).streak == 0 && StatsStore.get(b.getUUID()).streak == 0);
			check("the replay of that duel is a tie", ReplayStore.count() == 1 && ReplayStore.recent(null, 1).get(0).winner == -1);
			check("no offer is left behind", true);
			ReplayStore.delete(ReplayStore.recent(null, 1).get(0).id);
			Effects.resetForTest();
			Victory.resetForTest();
			Missions.resetForTest();
			ReplayCommands.resetForTest();
		});

		// ---------------------------------------------------------------- Delivery 9: totems and real health
		final float[] d9 = new float[6];
		step("totems: a duel to try them in", () -> {
			for (Dummy p : new Dummy[] {a, b}) p.inbox.clear();
			invite(a, b, new DuelConfig("qkit", false, WinMode.FIRST_TO, 5));
			check("a duel started", duel(a) != null);
		}, () -> phaseIs(a, Duel.Phase.FIGHTING), 600);
		step("totems: a lethal blow pops the totem and the round goes on", () -> {
			Duel d = duel(a);
			a.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			d9[0] = a.getStats().getValue(Stats.ITEM_USED, Items.TOTEM_OF_UNDYING);
			hit(b, a);
			check("the round did not end", d.phase == Duel.Phase.FIGHTING && d.points[0] == 0 && d.points[1] == 0, d.phase + " " + d.points[0] + "-" + d.points[1]);
			check("the totem is gone from the off hand", a.getOffhandItem().isEmpty(), String.valueOf(a.getOffhandItem()));
			check("Alice is alive with 1 health", a.isAlive() && a.getHealth() > 0f && a.getHealth() <= 1.01f && a.gameMode() == GameType.SURVIVAL, String.valueOf(a.getHealth()));
			check("the totem effects are on", a.hasEffect(MobEffects.REGENERATION) && a.hasEffect(MobEffects.ABSORPTION) && a.hasEffect(MobEffects.FIRE_RESISTANCE));
			check("the use of the totem counts in her statistics", a.getStats().getValue(Stats.ITEM_USED, Items.TOTEM_OF_UNDYING) == d9[0] + 1,
				a.getStats().getValue(Stats.ITEM_USED, Items.TOTEM_OF_UNDYING) + " vs " + d9[0]);
			hit(b, a);
			check("with no totem left the next lethal blow ends the round", d.phase == Duel.Phase.ROUND_END && d.points[1] == 1 && d.points[0] == 0, d.phase + " " + d.points[0] + "-" + d.points[1]);
		}, () -> roundIs(a, 2), 600);
		step("totems: in round 2 a totem in the main hand also counts", () -> {
			Duel d = duel(a);
			check("Alice is whole again", a.getHealth() == a.getMaxHealth() && !a.hasEffect(MobEffects.REGENERATION), String.valueOf(a.getHealth()));
			b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TOTEM_OF_UNDYING, 2));
			hit(a, b);
			check("Bob popped one of two totems", b.getMainHandItem().is(Items.TOTEM_OF_UNDYING) && b.getMainHandItem().getCount() == 1 && d.phase == Duel.Phase.FIGHTING && d.points[0] == 0,
				b.getMainHandItem() + " " + d.phase);
			hit(a, b);
			check("and then the second", b.getMainHandItem().isEmpty() && d.phase == Duel.Phase.FIGHTING && d.points[0] == 0, b.getMainHandItem() + " " + d.phase);
			hit(a, b);
			check("with none left Alice wins the round", d.phase == Duel.Phase.ROUND_END && d.points[0] == 1, d.phase + " " + d.points[0] + "-" + d.points[1]);
		}, () -> roundIs(a, 3), 600);
		step("notch: eating an enchanted golden apple gives absorption and regeneration", () -> {
			Duel d = duel(a);
			a.setHealth(6f);
			a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
			a.setItemInHand(InteractionHand.MAIN_HAND, a.getItemInHand(InteractionHand.MAIN_HAND).finishUsingItem((ServerLevel) a.level(), a));
			check("the apple gave absorption and regeneration", a.getAbsorptionAmount() >= 15.9f && a.hasEffect(MobEffects.REGENERATION), a.getAbsorptionAmount() + " " + a.hasEffect(MobEffects.REGENERATION));
			d9[1] = a.getHealth();
			softHit(b, a, 14f);
			check("a blow that would be lethal on 6 health alone does not end the round: the absorption takes it", d.phase == Duel.Phase.FIGHTING && d.points[1] == 1 && a.isAlive(),
				d.phase + " " + d.points[1] + " health " + a.getHealth() + " abs " + a.getAbsorptionAmount());
			check("the health did not go down", a.getHealth() >= d9[1] - 0.01f, a.getHealth() + " vs " + d9[1]);
			check("the blow was taken by the absorption", a.getAbsorptionAmount() < 15.9f, String.valueOf(a.getAbsorptionAmount()));
			d9[2] = ticks;
		}, () -> ticks >= d9[2] + 2, 50);
		step("notch: only when health and absorption are used up the round ends", () -> {
			Duel d = duel(a);
			check("two ticks later the apple still protects her", d.phase == Duel.Phase.FIGHTING && a.hasEffect(MobEffects.REGENERATION) && a.isAlive(), d.phase + " " + a.hasEffect(MobEffects.REGENERATION));
			hit(b, a);
			check("a huge blow ends the round", d.phase == Duel.Phase.ROUND_END && d.points[1] == 2, d.phase + " " + d.points[1]);
		}, () -> roundIs(a, 4), 600);
		step("totems: a blow that ignores totems (kill) still ends the round", () -> {
			Duel d = duel(a);
			a.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			a.invulnerableTime = 0;
			a.hurtServer((ServerLevel) a.level(), a.damageSources().genericKill(), 1000f);
			check("the round ended and the totem was not used", d.phase == Duel.Phase.ROUND_END && d.points[1] == 3 && a.getOffhandItem().is(Items.TOTEM_OF_UNDYING),
				d.phase + " " + d.points[1] + " " + a.getOffhandItem());
			run(a, "leave");
		}, () -> noDuel(a) && noDuel(b), 300);

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
