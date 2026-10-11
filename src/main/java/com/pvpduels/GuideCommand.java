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
		"Arena: flat 21x21 bedrock platform over the void (with or without walls). Only entities and block entities (chests, shulkers, crystals, boats...) can be placed. Falling off loses the round.",
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
		"/layout sort  - sort now. /layout key <1-3> <layout>  - choose which layout each Quick layout key uses (default 1, 2, 3)",
		"Keys (Options > Controls > PvP Duels): Sort inventory, Quick kit A/B/C, Quick layout 1/2/3, Next/Previous layout",
		"Rules can be categories (sword, spear, axe, mace, bow, crossbow, shield, totem, crystal, obsidian, gapple, pearl, potion, food, block...), exact item ids, or 'hand'."
	};

	private GuideCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("guide").executes(ctx -> {
			ctx.getSource().sendSuccess(() -> Msg.title("PvP Duels - commands"), false);
			for (String line : LINES) {
				ctx.getSource().sendSuccess(() -> Msg.info(line), false);
			}
			return 1;
		}));
	}
}
