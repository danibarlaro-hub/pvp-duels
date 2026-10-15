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
			{"Leave and Create+", "Leave: leaves the queue, cancels your duel requests and takes you to the lobby spawn.\n\nCreate+: Create Kit (the kit workshop) and Create Arena (Builders only: your own arenas) and the building contest."},
			{"NPCs", "Click them in the lobby:\nPractice Bot\nKit Workshop\nArena Builder\nExplosion Dummy: watch the film of your explosions on it\n\nAdmins: they appear by themselves. /npc setup, /npc add bot|kit|arena|preview, /npc remove, /npc list."},
			{"Lobby commands", "/lobby: go there\n/lobby return on|off: after a duel go to the lobby or back where you were\n\nAdmins: /setlobby, /lobby protect <radius|off>, /lobby clear"}
		}),
		new Topic("Duels", new String[][] {
			{"Challenge", "/duel <player> opens menus: kit, arena, version and how it is decided.\n\nThe other player gets [ACCEPT] / [DENY] (or /accept, /deny). It expires in 60 s."},
			{"During a duel", "Both use the challenger's kit; your own inventory comes back after.\n\n/leave quits\n/again asks for a rematch\n/duels lists the duels now, with [WATCH]"},
			{"How it is decided", "Rounds (max 100)\nFirst to N points\nTimed (max 30 min, a tie plays an extra round)\n\nUnlimited: it ends with /leave and the most points wins."},
			{"Arenas", "Flat: 21x21 bedrock over the void, with or without walls.\n\nCircular (radius 100/150/200): flat grassy terrain in invisible barriers. Build freely, it resets every round."},
			{"Unlimited arena", "Flat terrain, no border.\n\nMore than 100 blocks from your rival for 5 seconds: you lose half your health and are teleported next to them."},
			{"Rules", "In the menu you can switch off for that duel: natural regeneration, fall damage, hunger and building.\n\nFalling into the void loses the round."},
			{"Round mutators", "In the rules menu, click the nether star to pick a twist that both of you get: low gravity, speed, heavy hitters, double hearts, spotlight, or a random one at the start of every round."},
			{"Fair play", "5 minutes without a hit: one random player is teleported next to the other.\n\n/kit load and the Quick kit keys are blocked in a duel."},
			{"Watching", "/duels lists the duels now: click [WATCH].\n/spectate <player> or /watch <player> does the same.\n\n/leave stops watching and gives your things back."},
			{"After a duel", "You get a summary: how long it lasted, hits, damage, best combo, accuracy (swings that hit) and critical hits, and the totems, pearls, golden apples and potions each of you used.\n\nYou are protected for 5 s."},
			{"Totems and health", "A totem of undying in either hand saves you in a duel, like in vanilla: you stay in the round with 1 heart.\n\nYou lose a round only when you really die: health plus absorption, so a golden apple counts.\n\nThe chat shows the last blow."},
			{"Draw", "/draw offers a tie during a round (1v1 or 2v2). The rival clicks [ACCEPT] or uses /draw accept; /draw deny says no. It lasts 60 s.\n\nA tie ends the duel and breaks win streaks."},
			{"Replays", "Every 1v1 duel against a player is recorded. /replay lists yours.\n\nAn admin has to send you one: [ASK ADMIN] or /replay ask <id>. You get a message when they answer."},
			{"Watching replays", "/replay watch <id> plays it with a camera. Buttons: [PAUSE], [0.5x] [1x] [2x], [VIEW] (4 angles), [RESTART], [STOP].\n\nBlocks and pearls are not replayed."}
		}),
		new Topic("Practice", new String[][] {
			{"Combo drill", "/drill [kit] (or the clock in the Practice Bot menu): 60 seconds against a bot that never hits back.\n\nIt counts hits, best combo and accuracy, and saves your records. /drill records shows them."},
			{"Practice bot", "/botduel [kit] or the Practice Bot NPC. A bedrock arena, 100 blocks each way, with walls.\n\nStatic bot? Yes: it can't move. No: pick Passive, Easy, Normal or Hard."},
			{"Practice stats", "The sidebar counts hits, damage and combo. It goes on until /leave. A bot you knock out gets up again and you get a KO.\n\nPractice doesn't count for stats."},
			{"Test bot", "Admins: /testbot [name] [level] adds a fake player next to you that accepts every duel and /draw. Challenge it with /duel <name> to test alone.\n\n/testbot remove [name]"}
		}),
		new Topic("Queue", new String[][] {
			{"Queue", "/queue opens the menu. Wait for somebody and the duel starts by itself: flat arena with walls, first to 3 points.\n\nIn a party you wait together and meet another party (2v2).\n\nLeave with /queue leave or the [LEAVE QUEUE] button."},
			{"Three queues", "Public: pick a public kit (or a random one) and match with somebody who picked the same.\n\nRandom: the kit is a surprise.\n\nOwn kit: offer one of your kits; you both fight with it."},
			{"Queue commands", "/queue public [kit]\n/queue random\n/queue own <kit>\n/queue take <player>\n/queue list\n/queue leave"}
		}),
		new Topic("Arenas", new String[][] {
			{"Custom arenas", "/createarena or Create+ > Create Arena. Pick a template and build in a 64x64 plot.\n\nTo fight in one, pick Custom arena in the arena step of /duel."},
			{"Building one", "/setspawn 1 and 2: where each player starts\n/savearena <name>\n/leave: stop building (kept as a draft)\n\nChests and signs are not saved."},
			{"Share them", "/arena: your arenas, with [EDIT] and [PUBLISH]\n/arena public: the shared ones\n/arena publish <name>\n/arena unpublish <name>\n\nPrivate arenas are only for you."},
			{"Builder rank", "Only Builders make arenas. Place 1st, 2nd or 3rd in the weekly building contest to get it for 3 weeks.\n\n/builder shows yours."}
		}),
		new Topic("Contest", new String[][] {
			{"Building contest", "There is a category (a castle, a pirate ship...). Everybody can build it: /contest enter.\n\n5 days to build, then 2 days to vote. Then a 2-week break."},
			{"Your build", "Your plot is 64x64. Change at least 20 blocks.\n/contest save keeps it, /leave goes back. It is kept as your entry, and you can come back to improve it."},
			{"Voting", "/contest vote opens the gallery: the builds have numbers, not names.\n\nVisit them and click [VOTE]. One vote each, you can change it, and not for your own."},
			{"Prize", "The top 3 become Builders for 3 weeks and can make their own arenas (/createarena).\n\n/contest shows how it is going, /contest results the last one.\n\nAdmins: /contest start [category], /contest end"}
		}),
		new Topic("Kits", new String[][] {
			{"Your kits", "/kit save <name>\n/kit save <name> layout: the kit and its layout in one go\n/kit layout <name>: a layout from a saved kit\n/kit load <name>\n/kit list\n/kit show <name>\n/kit delete <name>"},
			{"Quick keys", "/kit key a|b|c <kit> sets what the Quick kit keys load.\n\n/kit profile <kit> <layout> sorts a kit when you load it."},
			{"Sharing", "/publicate <kit>\n/kit public\n/kit take <kit>\n/unpublish <kit>\n/private <kit> <player>: a locked copy"},
			{"Kit workshop", "Create+ > Create Kit, or /kit create [kit]. A creative void.\n\n/enchant <name> [0-255]\n/infinite <1-99>\n/kit save <name> [layout]\n/leave"}
		}),
		new Topic("Layouts", new String[][] {
			{"Layouts", "A layout sorts slots 1-36.\n/layout slot <1-36> <rules...>\n/layout inventorycopy\n/layout use <1-21|name>\n/layout auto builds one from your kits."},
			{"Sorting", "/layout sort or press R.\n\nSort also puts on the best armor you carry (never a piece with Curse of Binding). /layout armor off turns that off."},
			{"Keys", "Options > Controls > PvP Duels:\nSort inventory\nQuick kit A / B / C\nQuick layout 1 / 2 / 3\nNext / Previous layout\n\nAll can be changed."}
		}),
		new Topic("Ranking", new String[][] {
			{"Ranking", "/top shows the best by Elo. /top wins and /top streak sort by wins or best win streak.\n\nEveryone starts at 1000 Elo. Practice duels don't count."},
			{"Seasons", "A season lasts 28 days. /season shows the time left, your season Elo (it starts at 1000) and the top 3.\n\nAt the end, with 5 duels or more, the top 3 win an explosion they do not have. Then it starts again from zero."},
			{"Boards", "Floating boards with the top 10 by Elo, wins or win streak. They update by themselves after every duel.\n\nAdmins: stand where it should be and use /leaderboard place elo|wins|streak. /leaderboard remove takes the closest one away."},
			{"Ranking per kit", "/top kit lists the kits that have a ranking.\n/top kit <name> [elo|wins|streak] ranks the players with that kit.\n\nAfter a duel you also see your Elo with the kit."},
			{"Head to head", "/h2h lists the players you dueled and how you stand against each one.\n\n/h2h <player> shows the wins, losses, ties, rounds, the streak and the last duel. After every duel the chat tells you too."},
			{"Sidebar", "/sidebar turns the scoreboard on the right on or off: your Elo, wins, losses, streak and the duel score.\n\n/guide lists every command in chat."}
		}),
		new Topic("Achievements", new String[][] {
			{"Achievements", "/achievements lists all of them: what you unlocked and how far you are from the rest.\n\nYou win them by dueling, practicing, making kits and arenas and building in the contest."},
			{"Titles", "Some achievements give a title: Rookie, Duelist, Champion, Builder...\n\n/titles lists them: click a won one to wear it in front of your name; the locked ones show your progress. /titles off takes it off."},
			{"Profile", "/profile shows your Elo, wins, losses, best streak, title, how many achievements you have and your Builder rank.\n\n/profile <player> shows someone else's."}
		}),
		new Topic("Party & 2v2", new String[][] {
			{"Party", "/party invite <friend> asks a friend to be your partner. They click [ACCEPT].\n\nA party is exactly two players. /party leave ends it for both."},
			{"2v2 duels", "When both of you are in a party, /duel <player> challenges that player and their partner.\n\nA party only duels another party."},
			{"Rounds", "A player who is hit out watches until the round ends. A round is won when both rivals are out. No friendly fire.\n\nWin a round alone for the Clutch achievement."},
			{"Good to know", "/leave makes your team forfeit.\n\n2v2 does not change the Elo and can't be watched yet.\n\nA party can use /queue: both wait together and meet another party."}
		}),
		new Topic("Effects", new String[][] {
			{"Victory effects", "When you win, 3 fireworks go up in a row behind you, and your particles play for 5 seconds.\n\n/effects opens the menu to choose them. /effects preview shows them now (with an explosion equipped you get its film)."},
			{"The finale", "After a win the camera zooms out from the middle of the arena and rises 7 blocks. Everybody sees it, spectators too. Sneak to skip it.\n\nWith a Domain you get a real dome over the arena. Every big one has its own film."},
			{"Free camera", "In a film press Left Ctrl to leave its camera and fly around like a spectator (up to 70 blocks away). Press it again, or /freecam, to go back.\n\nRight Shift skips the film. Both keys are in Controls > PvP Duels."},
			{"Ties", "A tie breaks your win streak.\n\nTwo normal players, or one Domain Expansion against a normal player: the duel just ends.\n\nTwo Domain Expansions clash and push each other until they tire: I guess it's a tie."},
			{"Missions", "/missions shows 6 daily missions and 5 weekly ones (they reset on Monday). They count in duels against players.\n\nA duel counts for the day it started, even if it ends after midnight."},
			{"Rewards", "Each mission gives a firework color or shape, or a particle. A repeated particle is one more copy to use in another slot.\n\nExplosions are very rare: 0.8% daily, 1.5% weekly."},
			{"Your slots", "Particles: 2 on the floor and 4 around the arena.\n\nFireworks: up to 5 colors each.\n\nYou can have effects OR an explosion, not both. The menu can hide the effects of other players."},
			{"Explosions", "Very rare, 32: the Orbital Cannons, Shooting Star, Black Hole, Meteor Shower, Gungnir, Seven Stars, Railgun, Tesseract, Eclipse, Moonfall, Cronos Clock, Zeus Storm, 7 Domains and 12 newer ones.\n\nMissions unlock them."},
			{"Newest explosions", "Pillars of Fire, Tornado, Crystal Burst, Rift Storm, Dragon Strike, Titan Fist, Soul Reaper, Solar Flare, Supernova, Prism Beams, Spore Bloom and Sculk Awakening.\n\nEach has its own film and tune."},
			{"Win and lose sounds", "A short tune plays when somebody wins: fireworks and particles share one, every explosion has its own.\n\nThe loser gets a sad one (trombone, womp womp...).\n\nTurn them off in /effects. Only for you."},
			{"Silly sounds", "20 silly sounds (Fahhh, Bonk, Air horn, Sad trombone...) can play when somebody loses, instead of the sad tune.\n\n/effects has a button to turn them off. It is only for you."},
			{"Your losing sound", "When you lose, everybody who has the silly sounds on hears the sound YOU chose.\n\n/losesound opens the twenty (click one to hear it) or /losesound <name>. Random = a different one each time."},
			{"Effect quality", "Only for what you see: Potato, Low, Mid, High and Ultra Pro Max.\n\nPotato sends a tenth of the particles (slow computers), Ultra Pro Max double. Click it in /effects."}
		}),
		new Topic("About", new String[][] {
			{"About this server", "A place to duel your friends 1v1 with your own kits, in arenas made for it.\n\nThe world runs on the host's computer and is shared with Essential."},
			{"Bug reports", "Something broke? /bugreport and write what happened. It is saved in a file for the owner of the world, with the time and where you were.\n\nOne report every few seconds."},
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

		// a book page has 14 lines: the title and one line per topic (13 topics)
		MutableComponent index = Component.literal("PvP Duels\n").withStyle(ChatFormatting.DARK_BLUE, ChatFormatting.BOLD);
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
