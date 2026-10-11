package com.pvpduels;

import com.mojang.brigadier.CommandDispatcher;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class GuideCommand {
	private static final String[] LINES = {
		"/kit save <name>  - save your current inventory as a kit",
		"/kit load <name>  - replace your inventory with a kit",
		"/kit list  - list your kits",
		"/kit delete <name>  - delete a kit",
		"/kit profile <kit> <layout|none>  - sort a kit with a layout when it is loaded",
		"Layouts sort your WHOLE inventory. Slots: 1-9 hotbar, 10-36 backpack (left to right, top to bottom).",
		"/layout slot <1-36> <rules...>  - up to 4 priority levels, e.g. /layout slot 1 sword spear",
		"/layout level <slot> <1-4> <rule|none>  - change just one priority level",
		"/layout use <1-21|name>  - switch layout (numbers 1-21 are created on demand)",
		"/layout copy <from> <to>  - copy a layout; /layout new <name>  - create one",
		"/layout show | list | clear <slot|all> | delete <name>  - manage layouts (max 21)",
		"/layout sort  - sort now. /layout key <1-3> <layout>  - choose which layout each Quick layout key uses (default 1, 2, 3)",
		"Keys (Options > Controls > PvP Duels): Sort inventory, Quick layout 1/2/3, Next/Previous layout, Totem to offhand (spam it to refill your totem)",
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
