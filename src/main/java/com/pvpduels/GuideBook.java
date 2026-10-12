package com.pvpduels;

import com.pvpduels.duel.DuelManager;
import com.pvpduels.util.Prefs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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

	private static final String[][] PAGES = {
		{"PvP Duels", "Kits, layouts, duels and a kit workshop.\n\nEverything is a command: this book lists them all. /guide shows the same list in chat.\n\nTurn the page!"},
		{"Duels", "/duel <player> opens menus: kit, arena, version and how the duel is decided.\n\nThe other player gets [ACCEPT] / [DENY] buttons in chat (or /accept, /deny). Requests expire in 60 s."},
		{"During a duel", "Both use the challenger's kit. Your own inventory is saved and comes back after.\n\n/leave quits. /again asks for a rematch.\n/kit load and the Quick kit keys are blocked."},
		{"How it is decided", "Fixed rounds (max 100, or unlimited), First to N points, or Timed (max 30 min, a tie plays an extra round).\n\nUnlimited duels end when someone uses /leave: most points wins."},
		{"Rules", "Falling into the void loses the round.\n\n5 minutes without hitting each other: one random player is teleported next to the other.\n\nIn the flat arena only entities and block entities (chests, shulkers, crystals...) can be placed."},
		{"Kits", "/kit save <name>\n/kit load <name>\n/kit list\n/kit delete <name>\n/kit key a|b|c <kit>  sets the Quick kit keys\n/kit profile <kit> <layout>  sorts a kit when loaded"},
		{"Kit workshop", "/kit create [kit] opens a creative void: you can't place or break anything.\n\n/enchant <enchantment> [0-255]\n/infinite <1-99>\n/kit save <name>\n\n/leave or /kit exit brings your inventory back."},
		{"Layouts", "A layout sorts slots 1-36.\n/layout slot <1-36> <rules...> (32 priority levels)\n/layout inventorycopy\n/layout use <1-21|name>\n/layout sort or press R."},
		{"Keys", "Options > Controls > PvP Duels:\nSort inventory\nQuick kit A / B / C\nQuick layout 1 / 2 / 3\nNext / Previous layout\n\nAll of them can be changed."},
		{"Sidebar and stats", "/sidebar turns the scoreboard on the right on or off.\n\nIt shows your wins, losses, streak and rounds, and during a duel the score of both players.\n\n/guide book gives you this book again."}
	};

	private GuideBook() {}

	public static ItemStack create() {
		List<Filterable<Component>> pages = new ArrayList<>();
		for (String[] page : PAGES) {
			Component text = Component.literal(page[0] + "\n\n").withStyle(ChatFormatting.DARK_BLUE, ChatFormatting.BOLD)
				.append(Component.literal(page[1]).withStyle(ChatFormatting.BLACK));
			pages.add(Filterable.passThrough(text));
		}
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT,
			new WrittenBookContent(Filterable.passThrough("PvP Duels Guide"), "PvP Duels", 0, pages, true));
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(MARK, true);
		book.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return book;
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
			if (DuelManager.isInDuel(p.getUUID()) || has(p)) return;
			give(p);
		});
	}
}
