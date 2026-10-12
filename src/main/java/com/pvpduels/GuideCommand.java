package com.pvpduels;

import com.mojang.brigadier.CommandDispatcher;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class GuideCommand {
	private static final String[] LINES = {
		"LOBBY HOTBAR: while you stand in the lobby, slot 1 is Send Duel (sword: right-click to pick a player, or right-click / hit a player), slot 2 is Leave (barrier: cancels your duel requests and takes you to the lobby spawn), slot 8 is Create+ (grass block: Create Kit or Create Arena) and slot 9 is the guide book. They can't be moved; the things you had in those slots come back when you leave the lobby",
		"NPCS in the lobby: click the Practice Bot (practice menu), the Kit Workshop (make kits) or the Arena Builder (arenas). Admins: /npc add bot|kit|arena puts one where you stand, /npc remove deletes the closest, /npc list",
		"/duels  - the duels running right now, each with a [WATCH] button",
		"DUELS: /duel <player>  - choose kit, arena, version and how the duel is decided, then send the challenge",
		"/accept [player] or /duel accept [player]  - accept a challenge (you can also click [ACCEPT] in the chat); /deny [player]  - deny it. Requests expire after 60 seconds",
		"After every duel you get a summary: how long it lasted and, for each of you, hits, damage, best combo and the totems, ender pearls, golden apples and potions used",
		"/leave  - quit your duel (you lose, or it ends in a draw/by score if it was unlimited). /again  - ask for a rematch with the same settings",
		"In a duel both players use the challenger's kit, your own inventory is saved and given back, and /kit load + the Quick kit keys are blocked. 5 minutes without hitting each other teleports one player next to the other.",
		"Flat arena: 21x21 bedrock platform over the void (with or without walls). Only entities and block entities (chests, shulkers, crystals, boats...) can be placed. Falling off loses the round.",
		"Circular arena (radius 100, 150 or 200): flat normal terrain (5 dirt, stone, deepslate, bedrock) inside a ring of invisible barriers. You can build and dig freely; the terrain is reset after every round. Leaving the circle loses the round.",
		"Unlimited arena: the same terrain without a border. If you stay more than 100 blocks away from your rival for 5 seconds, you lose half of your health and are teleported next to them.",
		"PRACTICE: /botduel [kit] [static|passive|easy|normal|hard]  - practice alone against a bot in a big bedrock arena (100 blocks each way, with walls). The menu asks 'Static bot?': Yes = it can't be moved at all; No = it takes knockback, and you choose Passive (never fights back), Easy, Normal or Hard (it fights back). The sidebar counts your hits, damage and combo. It goes on until you use /leave; a bot you knock out gets up again and counts as a KO (nothing is reset); practice duels don't count for your stats",
		"PARTY AND 2v2: /party invite <player>  - ask a friend to be your partner for 2v2 (they click [ACCEPT] or use /party accept; /party deny; the invitation lasts 60 seconds). A party is exactly two players, /party shows yours and /party leave ends it for both. When both of you are in a party, /duel <player> challenges that player and their partner: same kit, arena and mode menus as a 1v1, four players in one arena. A party only duels another party (a single player can't duel a party). A player who is knocked out watches in spectator mode until the round ends; the round is won when both rivals are out; no friendly fire. /leave makes your team forfeit. 2v2 duels don't change the Elo, can't be watched yet and have no rematch. Achievements: Better Together, Dynamic Duo, Clutch, Clutch Master",
		"QUEUE: /queue  - wait for somebody and a duel starts by itself (flat arena with walls, first to 3 points). Public queue: pick a public kit (or a random one) and match with somebody who picked the same. Random queue: the kit is a surprise (a public kit, one of yours or your rival's, or one offered in the own kit queue). Own kit queue: offer one of your kits and wait, or take a kit somebody offers; you both fight with it. /queue public [kit], /queue random, /queue own <kit>, /queue take <player>, /queue list, /queue leave. The Leave barrier in the lobby also leaves the queue",
		"ARENAS: Builders (players who placed 1st, 2nd or 3rd in the weekly building contest; the rank lasts 3 weeks, /builder shows yours) make their own arenas: /createarena [void|flat|box] (or Create+ > Create Arena) takes you to a 64x64 plot in creative mode with a starting template you can change completely. /setspawn 1|2 marks where each player starts, /savearena <name> saves it, /leave stops building (your work is kept as a draft). Chests, signs and other block entities are not saved",
		"/arena  - your arenas, each with [EDIT] and [PUBLISH]/[UNPUBLISH]; /arena public  - the shared ones; /arena publish <name>, /arena unpublish <name>, /arena edit <name>, /arena delete <name>. Like kits, an arena is private until its Builder publishes it. To fight in one choose 'Custom arena' in the arena step of /duel (first to the score you pick; building is reset every round). Admins skip the rank and can give it: /builder give <player> [weeks], /builder remove <player>",
		"CONTEST: every week there is a building contest with a random category (a castle, a pirate ship...). Everybody can join: /contest shows the category and the time left, /contest enter takes you to your own 64x64 plot in creative mode (your things come back when you leave) and /contest save keeps your build without leaving. Your build is your entry when you leave (change at least 20 blocks) and you can come back to improve it. After 5 days of building, 2 days of voting: /contest vote opens a gallery of the builds without names, click one to visit it and use [VOTE] (one vote each, you can change it, never for your own build). The top 3 with at least one vote become Builders for 3 weeks. Then a new contest starts. /contest results shows the last one. Admins: /contest start [category], /contest end (skips to the next phase)",
		"ACHIEVEMENTS: /achievements  - the list of 40 achievements (duels won, streaks, Elo, combos, kits, practice, arenas, contest...), with your progress. Some give a title: /titles opens the ones you won and you click one to wear it in front of your name (chat, tab list, name tag); /titles off takes it off. /profile [player]  - Elo, wins, streak, title, achievements and Builder rank",
		"RANKING: /top [elo|wins|streak]  - the best players. Every duel against a real player changes your Elo rating (everybody starts at 1000); practice and /test_dummy duels don't count. /top kit lists the kits that have a ranking and /top kit <name> [elo|wins|streak] ranks the players with that kit (each kit has its own Elo; kits are told apart by name)",
		"WORKSHOP: /kit create [kit]  - go to the kit workshop (creative mode, nothing can be placed or broken; give a kit name to edit it). There use /enchant <enchantment> [level 0-255] and /infinite <1-99> on the item in your hand, then /kit save <name>. /leave or /kit exit  - go back to your own inventory",
		"DUEL RULES: in the /duel menu you can switch off natural regeneration, fall damage, hunger and (circular/unlimited arenas) building, for that duel only",
		"/spectate <player> (or /watch)  - watch a running duel in spectator mode (replaces the admin-only Minecraft /spectate); /leave stops watching. After a duel you get 5 seconds of protection",
		"LOBBY: an admin sets it with /setlobby (/lobby clear removes it). /lobby takes you there; after duels you are sent there unless you use /lobby return off. It is saved inside the world. /lobby protect <radius> (admin) stops building and fighting within that radius (admins in creative can still build); /lobby protect off turns it off",
		"SHARING: /publicate <kit>  - put a kit on the public shelf; /kit public  - list public kits (click [TAKE] or use /kit take <kit> to copy one); /unpublish <kit>  - take yours off the shelf; /private <kit> <player>  - give a locked copy to one player: they can use it but not share or edit it",
		"Admins: /test_dummy <name>  - adds a fake player that accepts every duel, to test alone; /test_dummy remove [name]",
		"/sidebar [on|off]  - the scoreboard on the right with your stats and the duel score. /guide book  - get the guide book again",
		"/kit save <name>  - save your current inventory as a kit",
		"/kit load <name>  - replace your inventory with a kit",
		"/kit list  - list your kits",
		"/kit show <name>  - look inside a saved kit (a chest you can only look at)",
		"/kit delete <name>  - delete a kit",
		"/kit key <a|b|c> <kit|none>  - choose which kit each Quick kit A/B/C key loads; /kit keys  - show them",
		"/kit profile <kit> <layout|none>  - sort a kit with a layout when it is loaded",
		"Layouts sort your WHOLE inventory. Slots: 1-9 hotbar, 10-36 backpack (left to right, top to bottom).",
		"/layout slot <1-36> <rules...>  - up to 32 priority levels, e.g. /layout slot 1 sword spear",
		"/layout level <slot> <1-32> <rule|none>  - change just one priority level",
		"/layout inventorycopy [layout] [priority]  - copy the items in your inventory into a layout at that priority (no layout = the active one, no priority = next free level). Copy several kits into one layout using priority 1, 2, 3...",
		"/layout use <1-21|name>  - switch layout (numbers 1-21 are created on demand)",
		"/layout copy <from> <to>  - copy a layout; /layout new <name>  - create one",
		"/layout show | list | clear <slot|all> | delete <name>  - manage layouts (max 21)",
		"/layout auto [name]  - builds a layout from your kits: every item (or category like sword) that is in the same slot in most of your kits gets that slot",
		"/layout armor on|off  - Sort also puts on the best armor you carry (armor points, toughness, Protection...). It never puts on a piece with Curse of Binding and never takes off one that has it. On by default",
		"/layout sort  - sort now. /layout key <1-3> <layout>  - choose which layout each Quick layout key uses (default 1, 2, 3)",
		"Keys (Options > Controls > PvP Duels): Sort inventory, Quick kit A/B/C, Quick layout 1/2/3, Next/Previous layout",
		"Rules can be categories (sword, spear, axe, mace, bow, crossbow, shield, totem, crystal, obsidian, gapple, pearl, potion, food, block...), exact item ids, or 'hand'."
	};

	private GuideCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("guide")
			.executes(ctx -> {
				ctx.getSource().sendSuccess(() -> Msg.title("PvP Duels - commands"), false);
				for (String line : LINES) {
					ctx.getSource().sendSuccess(() -> Msg.info(line), false);
				}
				return 1;
			})
			.then(Commands.literal("book").executes(ctx -> {
				GuideBook.give(ctx.getSource().getPlayerOrException());
				ctx.getSource().sendSuccess(() -> Msg.ok("Here is the guide book."), false);
				return 1;
			})));
	}
}
