package com.pvpduels;

import com.pvpduels.duel.DuelManager;
import com.pvpduels.duel.Lobby;
import com.pvpduels.util.Prefs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/** The guide book every player gets the first time they join. /guide book gives another one. */
public final class GuideBook {
	private static final String MARK = "pvpduels_guide";

	/** One topic of the book: a name for the index and its pages (title, text). A blank page follows every topic. */
	private record Topic(String name, String[][] pages) {}

	private static final Topic[] TOPICS = {
		new Topic("Lobby", new String[][] {
			{"The lobby hotbar", "Slot 1: Send Duel\nSlot 2: Leave\nSlot 8: Create+\nSlot 9: this book\n\nThey stay in place while you are in the lobby."},
			{"Send Duel", "Right-click: pick a player to challenge.\n\nRight-click or hit a player with it: challenge them straight away."},
			{"Leave and Create+", "Leave: leaves the queue, cancels your duel requests and takes you to the lobby spawn.\n\nCreate+: Create Kit (the kit workshop) and Create Arena (Builders only: your own arenas)."},
			{"NPCs", "Click them in the lobby:\nPractice Bot\nKit Workshop\nArena Builder\n\nAdmins: /npc add bot|kit|arena, /npc remove, /npc list."},
			{"Lobby commands", "/lobby: go there\n/lobby return on|off: after a duel go to the lobby or back where you were\n\nAdmins: /setlobby, /lobby protect <radius|off>, /lobby clear"}
		}),
		new Topic("Duels", new String[][] {
			{"Challenge", "/duel <player> opens menus: kit, arena, version and how it is decided.\n\nThe other player gets [ACCEPT] / [DENY] (or /accept, /deny). It expires in 60 s."},
			{"During a duel", "Both use the challenger's kit; your own inventory comes back after.\n\n/leave quits\n/again asks for a rematch\n/duels lists the duels now, with [WATCH]"},
			{"How it is decided", "Rounds (max 100)\nFirst to N points\nTimed (max 30 min, a tie plays an extra round)\n\nUnlimited: it ends with /leave and the most points wins."},
			{"Arenas", "Flat: 21x21 bedrock over the void, with or without walls.\n\nCircular (radius 100/150/200): flat terrain in invisible barriers. Build freely, it resets every round."},
			{"Unlimited arena", "Flat terrain, no border.\n\nMore than 100 blocks from your rival for 5 seconds: you lose half your health and are teleported next to them."},
			{"Rules", "In the menu you can switch off for that duel: natural regeneration, fall damage, hunger and building.\n\nFalling into the void loses the round."},
			{"Fair play", "5 minutes without a hit: one random player is teleported next to the other.\n\n/kit load and the Quick kit keys are blocked in a duel."},
			{"After a duel", "You get a summary: how long it lasted, hits, damage, best combo, and the totems, pearls, golden apples and potions each of you used.\n\nYou are protected for 5 s."}
		}),
		new Topic("Practice", new String[][] {
			{"Practice bot", "/botduel [kit] or the Practice Bot NPC. A bedrock arena, 100 blocks each way, with walls.\n\nStatic bot? Yes: it can't move. No: pick Passive, Easy, Normal or Hard."},
			{"Practice stats", "The sidebar counts hits, damage and combo. It goes on until /leave. A bot you knock out gets up again and you get a KO.\n\nPractice doesn't count for stats."}
		}),
		new Topic("Queue", new String[][] {
			{"Queue", "/queue opens the menu. Wait for somebody and the duel starts by itself: flat arena with walls, first to 3 points.\n\nLeave with the Leave barrier, /queue leave or the [LEAVE QUEUE] button."},
			{"Three queues", "Public: pick a public kit (or a random one) and match with somebody who picked the same.\n\nRandom: the kit is a surprise.\n\nOwn kit: offer one of your kits; you both fight with it."},
			{"Queue commands", "/queue public [kit]\n/queue random\n/queue own <kit>\n/queue take <player>\n/queue list\n/queue leave"}
		}),
		new Topic("Arenas", new String[][] {
			{"Custom arenas", "/createarena or Create+ > Create Arena. Pick a template and build in a 64x64 plot.\n\nTo fight in one, pick Custom arena in the arena step of /duel."},
			{"Building one", "/setspawn 1 and 2: where each player starts\n/savearena <name>\n/leave: stop building (kept as a draft)\n\nChests and signs are not saved."},
			{"Share them", "/arena: your arenas, with [EDIT] and [PUBLISH]\n/arena public: the shared ones\n/arena publish <name>\n/arena unpublish <name>\n\nPrivate arenas are only for you."},
			{"Builder rank", "Only Builders make arenas. Place 1st, 2nd or 3rd in the weekly building contest to get it for 3 weeks.\n\n/builder shows yours."}
		}),
		new Topic("Kits", new String[][] {
			{"Your kits", "/kit save <name>\n/kit load <name>\n/kit list\n/kit show <name>\n/kit delete <name>"},
			{"Quick keys", "/kit key a|b|c <kit> sets what the Quick kit keys load.\n\n/kit profile <kit> <layout> sorts a kit when you load it."},
			{"Sharing", "/publicate <kit>\n/kit public\n/kit take <kit>\n/unpublish <kit>\n/private <kit> <player>: a locked copy"},
			{"Kit workshop", "Create+ > Create Kit, or /kit create [kit]. A creative void.\n\n/enchant <name> [0-255]\n/infinite <1-99>\n/kit save <name>\n/leave"}
		}),
		new Topic("Layouts", new String[][] {
			{"Layouts", "A layout sorts slots 1-36.\n/layout slot <1-36> <rules...>\n/layout inventorycopy\n/layout use <1-21|name>\n/layout auto builds one from your kits."},
			{"Sorting", "/layout sort or press R.\n\nSort also puts on the best armor you carry (never a piece with Curse of Binding). /layout armor off turns that off."},
			{"Keys", "Options > Controls > PvP Duels:\nSort inventory\nQuick kit A / B / C\nQuick layout 1 / 2 / 3\nNext / Previous layout\n\nAll can be changed."}
		}),
		new Topic("Ranking", new String[][] {
			{"Ranking", "/top shows the best by Elo. /top wins and /top streak sort by wins or best win streak.\n\nEveryone starts at 1000 Elo. Practice duels don't count."},
			{"Ranking per kit", "/top kit lists the kits that have a ranking.\n/top kit <name> [elo|wins|streak] ranks the players with that kit.\n\nAfter a duel you also see your Elo with the kit."},
			{"Sidebar", "/sidebar turns the scoreboard on the right on or off: your Elo, wins, losses, streak and the duel score.\n\n/guide lists every command in chat."}
		}),
		new Topic("Spectating", new String[][] {
			{"Watching", "/duels lists the duels now: click [WATCH].\n/spectate <player> or /watch <player> does the same.\n\n/leave stops watching and gives your things back."}
		}),
		new Topic("About", new String[][] {
			{"About this server", "A place to duel your friends 1v1 with your own kits, in arenas made for it.\n\nThe world runs on the host's computer and is shared with Essential."},
			{"Have fun", "Everything is a command or a click. If you get lost, this book is always in slot 9 and /guide lists the commands.\n\nBe fair and good luck!"}
		})
	};

	private GuideBook() {}

	/** The first page is the index; every topic is followed by a blank page. */
	public static ItemStack create() {
		List<Filterable<Component>> pages = new ArrayList<>();
		int[] firstPage = new int[TOPICS.length];
		int next = 2; // pages count from 1 and the index is page 1
		for (int t = 0; t < TOPICS.length; t++) {
			firstPage[t] = next;
			next += TOPICS[t].pages().length + 1;
		}

		MutableComponent index = Component.literal("PvP Duels\n").withStyle(ChatFormatting.DARK_BLUE, ChatFormatting.BOLD)
			.append(Component.literal("Click a topic:\n\n").withStyle(ChatFormatting.DARK_GRAY));
		for (int t = 0; t < TOPICS.length; t++) {
			int page = firstPage[t];
			index.append(Component.literal("> " + TOPICS[t].name() + "\n").withStyle(style -> style.withColor(ChatFormatting.DARK_AQUA)
				.withClickEvent(new ClickEvent.ChangePage(page))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Go to page " + page)))));
		}
		pages.add(Filterable.passThrough(index));

		for (int t = 0; t < TOPICS.length; t++) {
			for (String[] page : TOPICS[t].pages()) {
				Component text = Component.literal(page[0] + "\n\n").withStyle(ChatFormatting.DARK_BLUE, ChatFormatting.BOLD)
					.append(Component.literal(page[1]).withStyle(ChatFormatting.BLACK));
				pages.add(Filterable.passThrough(text));
			}
			pages.add(Filterable.passThrough(Component.literal(""))); // the blank page that separates topics
		}
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT,
			new WrittenBookContent(Filterable.passThrough("PvP Duels Guide"), "PvP Duels", 0, pages, true));
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(MARK, true);
		book.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return book;
	}

	/** How many topics the book has (each one is followed by a blank page). */
	public static int topicCount() {
		return TOPICS.length;
	}

	/** The number of pages of the book (the index, every topic and its blank page). */
	public static int pageCount() {
		int n = 1;
		for (Topic t : TOPICS) n += t.pages().length + 1;
		return n;
	}

	public static boolean isGuide(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return false;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(MARK);
	}

	public static boolean has(ServerPlayer p) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isGuide(inv.getItem(i))) return true;
		}
		return false;
	}

	public static void give(ServerPlayer p) {
		ItemStack book = create();
		if (!p.getInventory().add(book)) p.drop(book, false);
	}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer p = handler.player;
			if (Prefs.bookGiven(p.getUUID())) return;
			Prefs.markBookGiven(p.getUUID());
			// With a lobby the book is in slot 9 there; without one it goes into the inventory once.
			if (Lobby.isSet() || DuelManager.isInDuel(p.getUUID()) || has(p)) return;
			give(p);
		});
	}
}
