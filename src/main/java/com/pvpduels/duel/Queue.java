package com.pvpduels.duel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.kit.KitStore;
import com.pvpduels.kit.PublicKits;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * /queue: wait for somebody to play with, without having to ask. There are three queues:
 * <ul>
 * <li><b>public</b>: you pick a public kit (or "random public kit"); you are matched with somebody who picked the same one.</li>
 * <li><b>random</b>: the kit is a surprise. It comes from the public shelf, from your kits, from your rival's kits or from a
 * kit somebody is offering in the own-kit queue.</li>
 * <li><b>own kit</b>: you offer one of your kits and wait. Somebody who opens this queue sees what is offered and can take it:
 * the two of you then fight with the kit of the one who waited.</li>
 * </ul>
 * A match starts a duel at once: flat arena with walls, first to 3 points. Leave with the Leave barrier, /queue leave or the
 * [LEAVE QUEUE] button.
 */
public final class Queue {
	public enum Mode { PUBLIC, RANDOM, OWN }

	/** kit: the public / own kit chosen, or null for "random". */
	record Entry(UUID id, String name, Mode mode, String kit, int since) {}

	/** A kit that can be played: where to find it and how to name it. */
	record Choice(UUID owner, String name, boolean onShelf, String source) {}

	private static final List<Entry> ENTRIES = new ArrayList<>();
	private static Random random = new Random();
	private static int tick;

	private Queue() {}

	// ------------------------------------------------------------------ state

	public static boolean isQueued(UUID id) {
		return entryOf(id) != null;
	}

	static Entry entryOf(UUID id) {
		for (Entry e : ENTRIES) if (e.id().equals(id)) return e;
		return null;
	}

	static int size() {
		return ENTRIES.size();
	}

	/** For the self-test: a random generator that always gives the same numbers. */
	static void useRandom(Random r) {
		random = r;
	}

	static void clearAll() {
		ENTRIES.clear();
	}

	/** Takes the player out of the queue. Returns true when they were in it. */
	public static boolean leave(ServerPlayer p, boolean say) {
		Entry e = entryOf(p.getUUID());
		if (e == null) return false;
		ENTRIES.remove(e);
		if (say) p.sendSystemMessage(Msg.ok("You left the " + label(e) + " queue."));
		return true;
	}

	private static String label(Entry e) {
		return switch (e.mode()) {
			case PUBLIC -> "public" + (e.kit() == null ? " (random public kit)" : " (" + e.kit() + ")");
			case RANDOM -> "random";
			case OWN -> "own kit (" + e.kit() + ")";
		};
	}

	// ------------------------------------------------------------------ joining

	private static String canQueue(ServerPlayer p) {
		if (DuelManager.isBusy(p.getUUID())) return "You are busy: use /leave to get out of the duel, the workshop or spectator mode first.";
		if (BotDuel.isBot(p.getUUID())) return "Bots don't queue.";
		return null;
	}

	/** Public queue with a kit from the shelf, or with kit == null for a random public kit. Returns an error text or null. */
	public static String joinPublic(ServerPlayer p, String kit) {
		String problem = canQueue(p);
		if (problem != null) return problem;
		if (kit != null && PublicKits.find(kit).isEmpty()) return "There is no public kit named '" + kit + "'. See /kit public.";
		if (kit == null && PublicKits.names().isEmpty()) return "There are no public kits yet. Share one with /publicate <kit>.";
		return add(p, Mode.PUBLIC, kit);
	}

	/** The random queue. */
	public static String joinRandom(ServerPlayer p) {
		String problem = canQueue(p);
		if (problem != null) return problem;
		return add(p, Mode.RANDOM, null);
	}

	/** The own-kit queue: you offer this kit and wait for somebody to take it. */
	public static String joinOwn(ServerPlayer p, String kit) {
		String problem = canQueue(p);
		if (problem != null) return problem;
		if (!KitStore.exists(p.getUUID(), kit)) return "You have no kit named '" + kit + "'. Use /kit list.";
		try {
			var read = KitStore.read(p.level().getServer(), p.getUUID(), kit);
			if (read.isEmpty() || KitStore.countStacks(read.get().slots()) == 0) return "Kit '" + kit + "' is empty: fill it first.";
		} catch (IOException | RuntimeException e) {
			return "Could not read the kit: " + e.getMessage();
		}
		return add(p, Mode.OWN, kit);
	}

	/** Takes the kit of somebody who is waiting in the own-kit queue: you two fight with it. */
	public static String take(ServerPlayer p, String waitingName) {
		String problem = canQueue(p);
		if (problem != null) return problem;
		Entry offer = null;
		for (Entry e : ENTRIES) {
			if (e.mode() == Mode.OWN && e.name().equalsIgnoreCase(waitingName) && !e.id().equals(p.getUUID())) offer = e;
		}
		if (offer == null) return waitingName + " is not waiting with a kit right now. See /queue list.";
		MinecraftServer server = p.level().getServer();
		ServerPlayer owner = server.getPlayerList().getPlayer(offer.id());
		if (owner == null || DuelManager.isBusy(owner.getUUID())) {
			ENTRIES.remove(offer);
			return waitingName + " is not available any more.";
		}
		KitStore.Kit kit;
		try {
			var read = KitStore.read(server, offer.id(), offer.kit());
			if (read.isEmpty()) {
				ENTRIES.remove(offer);
				return "The kit '" + offer.kit() + "' no longer exists.";
			}
			kit = read.get();
		} catch (IOException | RuntimeException e) {
			return "Could not read the kit: " + e.getMessage();
		}
		leave(p, false);
		startMatch(server, owner, p, offer.id(), offer.kit(), kit, "own kit queue: " + offer.name() + "'s " + offer.kit());
		return null;
	}

	private static String add(ServerPlayer p, Mode mode, String kit) {
		leave(p, false);
		Entry mine = new Entry(p.getUUID(), p.getName().getString(), mode, kit, tick);
		ENTRIES.add(mine);
		p.sendSystemMessage(Msg.ok("You joined the " + label(mine) + " queue.").copy().append(Component.literal("  "))
			.append(Msg.button("[LEAVE QUEUE]", ChatFormatting.RED, "/queue leave", "Leave the queue")));
		if (mode != Mode.OWN) tryMatch(p.level().getServer(), mine);
		else p.sendSystemMessage(Msg.info("Waiting for somebody to take your kit '" + kit + "'. Others see it in /queue."));
		return null;
	}

	// ------------------------------------------------------------------ matching

	private static void tryMatch(MinecraftServer server, Entry mine) {
		Entry other = null;
		for (Entry e : ENTRIES) {
			if (e == mine || e.id().equals(mine.id()) || e.mode() != mine.mode()) continue;
			if (mine.mode() == Mode.PUBLIC && !sameKit(e.kit(), mine.kit())) continue;
			ServerPlayer waiting = server.getPlayerList().getPlayer(e.id());
			if (waiting == null || DuelManager.isBusy(waiting.getUUID())) continue; // the tick will take it out of the queue
			other = e;
			break; // the one who waited longest
		}
		if (other == null) return;
		ServerPlayer first = server.getPlayerList().getPlayer(other.id());
		ServerPlayer second = server.getPlayerList().getPlayer(mine.id());
		if (first == null || second == null) {
			if (first == null) ENTRIES.remove(other);
			return;
		}
		Choice choice = null;
		KitStore.Kit kit = null;
		// The random queue may pick an empty or deleted kit: try a few times before giving up.
		for (int attempt = 0; attempt < (mine.mode() == Mode.PUBLIC ? 1 : 8) && kit == null; attempt++) {
			choice = mine.mode() == Mode.PUBLIC ? publicChoice(mine.kit()) : randomChoice(first, second);
			if (choice != null) kit = load(server, choice);
		}
		if (kit == null) {
			String text = choice == null ? "A match was found but there is no kit to play with. Share a kit with /publicate first."
				: "A match was found but the kit '" + choice.name() + "' could not be read. Try the queue again.";
			first.sendSystemMessage(Msg.err(text));
			second.sendSystemMessage(Msg.err(text));
			leave(first, false);
			leave(second, false);
			return;
		}
		leave(first, false);
		leave(second, false);
		startMatch(server, first, second, choice.owner(), choice.name(), kit, choice.source());
	}

	private static boolean sameKit(String a, String b) {
		return a == null ? b == null : a.equalsIgnoreCase(b);
	}

	private static Choice publicChoice(String kit) {
		if (kit != null) {
			var entry = PublicKits.find(kit);
			return entry.map(e -> new Choice(e.owner(), e.name(), true, "public kit")).orElse(null);
		}
		List<String> names = PublicKits.names();
		if (names.isEmpty()) return null;
		String pick = names.get(random.nextInt(names.size()));
		return PublicKits.find(pick).map(e -> new Choice(e.owner(), e.name(), true, "random public kit")).orElse(null);
	}

	/** Picks a source at random (public shelf, the two players' kits, kits offered in the own-kit queue), then a kit from it. */
	static Choice randomChoice(ServerPlayer a, ServerPlayer b) {
		List<String> shelf = PublicKits.names();
		List<String> mineA = KitStore.list(a.getUUID());
		List<String> mineB = KitStore.list(b.getUUID());
		List<Entry> offers = new ArrayList<>();
		for (Entry e : ENTRIES) if (e.mode() == Mode.OWN) offers.add(e);
		int kinds = (shelf.isEmpty() ? 0 : 1) + (mineA.isEmpty() ? 0 : 1) + (mineB.isEmpty() ? 0 : 1) + (offers.isEmpty() ? 0 : 1);
		if (kinds == 0) return null;
		int n = random.nextInt(kinds); // which of the sources that have kits
		if (!shelf.isEmpty() && n-- == 0) {
			Choice c = publicChoice(shelf.get(random.nextInt(shelf.size())));
			return c == null ? null : new Choice(c.owner(), c.name(), true, "random queue: public kit " + c.name());
		}
		if (!mineA.isEmpty() && n-- == 0) {
			String name = mineA.get(random.nextInt(mineA.size()));
			return new Choice(a.getUUID(), name, false, "random queue: " + a.getName().getString() + "'s kit " + name);
		}
		if (!mineB.isEmpty() && n-- == 0) {
			String name = mineB.get(random.nextInt(mineB.size()));
			return new Choice(b.getUUID(), name, false, "random queue: " + b.getName().getString() + "'s kit " + name);
		}
		Entry offer = offers.get(random.nextInt(offers.size()));
		return new Choice(offer.id(), offer.kit(), false, "random queue: " + offer.name() + "'s kit " + offer.kit());
	}

	private static KitStore.Kit load(MinecraftServer server, Choice choice) {
		try {
			if (choice.onShelf()) {
				KitStore.Kit kit = KitStore.parse(server, choice.name(), PublicKits.raw(choice.name()));
				return KitStore.countStacks(kit.slots()) == 0 ? null : kit;
			}
			var read = KitStore.read(server, choice.owner(), choice.name());
			return read.isPresent() && KitStore.countStacks(read.get().slots()) > 0 ? read.get() : null;
		} catch (IOException | RuntimeException e) {
			PvpDuelsMod.LOGGER.warn("Queue: could not read the kit {}: {}", choice.name(), e.toString());
			return null;
		}
	}

	private static void startMatch(MinecraftServer server, ServerPlayer first, ServerPlayer second, UUID kitOwner, String kitName, KitStore.Kit kit, String source) {
		for (ServerPlayer p : new ServerPlayer[] {first, second}) {
			ServerPlayer rival = p == first ? second : first;
			p.sendSystemMessage(Msg.title("Match found!"));
			p.sendSystemMessage(Msg.info("  " + rival.getName().getString() + " - kit " + kitName + " (" + source + "). Flat arena, first to 3 points."));
		}
		DuelConfig config = new DuelConfig(kitName, true, WinMode.FIRST_TO, 3);
		if (!DuelManager.startMatched(server, first, second, kitOwner, config, kit)) {
			first.sendSystemMessage(Msg.err("The duel could not start. Join the queue again."));
			second.sendSystemMessage(Msg.err("The duel could not start. Join the queue again."));
		}
	}

	// ------------------------------------------------------------------ menus

	/** /queue: the three queues, and a way out when you are in one. */
	public static void openMenu(ServerPlayer p) {
		Entry mine = entryOf(p.getUUID());
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(10, DuelMenus.icon(Items.ENDER_CHEST, "Public queue", ChatFormatting.AQUA,
			"Pick a public kit (or a random one).", "You play against somebody who picked the same."), Queue::openPublicMenu);
		b.set(13, DuelMenus.icon(Items.ENDER_PEARL, "Random queue", ChatFormatting.LIGHT_PURPLE,
			"The kit is a surprise:", "a public one, one of yours or your rival's,", "or one offered in the own-kit queue."), who -> {
				who.closeContainer();
				report(who, joinRandom(who));
			});
		b.set(16, DuelMenus.icon(Items.CHEST, "Own kit queue", ChatFormatting.GOLD,
			"Offer one of your kits and wait,", "or take a kit somebody offers.", "You both fight with it."), Queue::openOwnMenu);
		if (mine != null) {
			b.set(22, DuelMenus.icon(Items.BARRIER, "Leave the queue", ChatFormatting.RED, "You are in the " + label(mine) + " queue."), who -> {
				who.closeContainer();
				leave(who, true);
			});
		}
		ChoiceMenu.open(p, Component.literal("Queue" + (mine == null ? "" : " (waiting)")), 3, b);
	}

	private static void report(ServerPlayer p, String problem) {
		if (problem != null) p.sendSystemMessage(Msg.err(problem));
	}

	private static ItemStack kitIcon(MinecraftServer server, PublicKits.Entry entry) {
		ItemStack icon = new ItemStack(Items.CHEST);
		try {
			KitStore.Kit kit = KitStore.parse(server, entry.name(), PublicKits.raw(entry.name()));
			for (ItemStack s : kit.slots()) {
				if (!s.isEmpty()) {
					icon = s.copy();
					icon.setCount(1);
					break;
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// the plain chest is fine
		}
		return DuelMenus.withName(icon, entry.name(), ChatFormatting.AQUA, "by " + entry.ownerName(), "Click to queue with this kit");
	}

	private static void openPublicMenu(ServerPlayer p) {
		MinecraftServer server = p.level().getServer();
		List<PublicKits.Entry> shelf = PublicKits.list();
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		b.set(4, DuelMenus.icon(Items.ENDER_EYE, "Random public kit", ChatFormatting.LIGHT_PURPLE, "Any public kit, picked when you match."), who -> {
			who.closeContainer();
			report(who, joinPublic(who, null));
		});
		for (int i = 0; i < Math.min(36, shelf.size()); i++) {
			PublicKits.Entry entry = shelf.get(i);
			b.set(9 + i, kitIcon(server, entry), who -> {
				who.closeContainer();
				report(who, joinPublic(who, entry.name()));
			});
		}
		if (shelf.isEmpty()) b.set(22, DuelMenus.icon(Items.PAPER, "No public kits yet", ChatFormatting.GRAY, "Share one with /publicate <kit>."));
		b.set(45, DuelMenus.icon(Items.ARROW, "Back", ChatFormatting.YELLOW), Queue::openMenu);
		ChoiceMenu.open(p, Component.literal("Public queue: pick a kit"), 6, b);
	}

	private static void openOwnMenu(ServerPlayer p) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		int slot = 9;
		for (Entry e : ENTRIES) {
			if (e.mode() != Mode.OWN || e.id().equals(p.getUUID()) || slot >= 45) continue;
			String waiting = e.name();
			b.set(slot++, DuelMenus.icon(Items.CHEST, waiting + "'s kit: " + e.kit(), ChatFormatting.GOLD, "Waiting for a rival.", "Click to fight with this kit."), who -> {
				who.closeContainer();
				report(who, take(who, waiting));
			});
		}
		if (slot == 9) b.set(22, DuelMenus.icon(Items.PAPER, "Nobody is offering a kit right now", ChatFormatting.GRAY, "Offer one of yours below."));
		b.set(4, DuelMenus.icon(Items.WRITABLE_BOOK, "Offer one of my kits", ChatFormatting.GREEN, "Wait for somebody to take it."), who ->
			DuelMenus.pickKit(who, "Own kit queue: offer a kit", 0, (w, name) -> {
				w.closeContainer();
				report(w, joinOwn(w, name));
			}));
		b.set(45, DuelMenus.icon(Items.ARROW, "Back", ChatFormatting.YELLOW), Queue::openMenu);
		ChoiceMenu.open(p, Component.literal("Own kit queue"), 6, b);
	}

	// ------------------------------------------------------------------ events and commands

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(Queue::onTick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> leave(handler.player, false));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ENTRIES.clear());
	}

	private static void onTick(MinecraftServer server) {
		tick++;
		if (tick % 20 != 0 || ENTRIES.isEmpty()) return;
		for (Entry e : new ArrayList<>(ENTRIES)) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.id());
			if (p == null || DuelManager.isBusy(p.getUUID())) {
				ENTRIES.remove(e);
				continue;
			}
			if (e.mode() == Mode.OWN && !KitStore.exists(e.id(), e.kit())) {
				ENTRIES.remove(e);
				p.sendSystemMessage(Msg.err("The kit '" + e.kit() + "' you offered no longer exists, so you left the queue."));
				continue;
			}
			int seconds = (tick - e.since()) / 20;
			p.sendOverlayMessage(Component.literal("In the " + label(e) + " queue  " + seconds / 60 + ":" + String.format("%02d", seconds % 60)
				+ "  (Leave barrier or /queue leave to stop)").withStyle(ChatFormatting.AQUA));
		}
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("queue")
			.executes(ctx -> {
				openMenu(ctx.getSource().getPlayerOrException());
				return 1;
			})
			.then(Commands.literal("leave").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				if (!leave(p, true)) {
					ctx.getSource().sendFailure(Msg.err("You are not in a queue."));
					return 0;
				}
				return 1;
			}))
			.then(Commands.literal("public")
				.executes(ctx -> answer(ctx.getSource(), joinPublic(ctx.getSource().getPlayerOrException(), null)))
				.then(Commands.argument("kit", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PublicKits.names(), builder))
					.executes(ctx -> answer(ctx.getSource(), joinPublic(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "kit"))))))
			.then(Commands.literal("random")
				.executes(ctx -> answer(ctx.getSource(), joinRandom(ctx.getSource().getPlayerOrException()))))
			.then(Commands.literal("own")
				.then(Commands.argument("kit", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KitStore.list(ctx.getSource().getPlayerOrException().getUUID()), builder))
					.executes(ctx -> answer(ctx.getSource(), joinOwn(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "kit"))))))
			.then(Commands.literal("take")
				.then(Commands.argument("player", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						List<String> names = new ArrayList<>();
						for (Entry e : ENTRIES) if (e.mode() == Mode.OWN) names.add(e.name());
						return SharedSuggestionProvider.suggest(names, builder);
					})
					.executes(ctx -> answer(ctx.getSource(), take(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "player"))))))
			.then(Commands.literal("list").executes(ctx -> {
				if (ENTRIES.isEmpty()) {
					ctx.getSource().sendSuccess(() -> Msg.info("Nobody is in a queue. /queue opens the menu."), false);
					return 1;
				}
				ctx.getSource().sendSuccess(() -> Msg.title("In the queues (" + ENTRIES.size() + ")"), false);
				for (Entry e : ENTRIES) ctx.getSource().sendSuccess(() -> Msg.info("  " + e.name() + ": " + label(e)), false);
				return ENTRIES.size();
			})));
	}

	private static int answer(CommandSourceStack source, String problem) {
		if (problem != null) {
			source.sendFailure(Msg.err(problem));
			return 0;
		}
		return 1;
	}
}
