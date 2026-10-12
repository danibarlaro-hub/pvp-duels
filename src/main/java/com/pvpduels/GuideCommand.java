package com.pvpduels;

import com.mojang.brigadier.CommandDispatcher;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class GuideCommand {
	private static final String[] LINES = {
		"DUELS: /duel <player>  - choose kit, arena, version and how the duel is decided, then send the challenge",
		"/accept [player] or /duel accept [player]  - accept a challenge (you can also click [ACCEPT] in the chat); /deny [player]  - deny it. Requests expire after 60 seconds",
		"/leave  - quit your duel (you lose, or it ends in a draw/by score if it was unlimited). /again  - ask for a rematch with the same settings",
		"In a duel both players use the challenger's kit, your own inventory is saved and given back, and /kit load + the Quick kit keys are blocked. 5 minutes without hitting each other teleports one player next to the other.",
		"Flat arena: 21x21 bedrock platform over the void (with or without walls). Only entities and block entities (chests, shulkers, crystals, boats...) can be placed. Falling off loses the round.",
		"Circular arena (radius 100, 150 or 200): flat normal terrain (5 dirt, stone, deepslate, bedrock) inside a ring of invisible barriers. You can build and dig freely; the terrain is reset after every round. Leaving the circle loses the round.",
		"Unlimited arena: the same terrain without a border. If you stay more than 100 blocks away from your rival for 5 seconds, you lose half of your health and are teleported next to them.",
		"PRACTICE: /botduel [kit] [static|passive|easy|normal|hard]  - practice alone against a bot in a big bedrock arena (100 blocks each way, with walls). The menu asks 'Static bot?': Yes = it can't be moved at all; No = it takes knockback, and you choose Passive (never fights back), Easy, Normal or Hard (it fights back). The sidebar counts your hits, damage and combo. Rounds go on until you use /leave; practice duels don't count for your stats",
		"RANKING: /top [elo|wins|streak]  - the best players. Every duel against a real player changes your Elo rating (everybody starts at 1000); practice and /test_dummy duels don't count",
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
