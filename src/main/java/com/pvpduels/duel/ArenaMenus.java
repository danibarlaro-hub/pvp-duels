package com.pvpduels.duel;

import com.pvpduels.arena.ArenaStore;
import com.pvpduels.arena.ArenaStudio;
import com.pvpduels.arena.ArenaTemplates;
import com.pvpduels.arena.BuilderRank;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/** The chest menus about custom arenas: the studio (Create Arena) and the list you pick an arena from when you challenge somebody. */
public final class ArenaMenus {
	private ArenaMenus() {}

	private static Item iconOf(ArenaTemplates.Kind kind) {
		return switch (kind) {
			case VOID -> Items.GLASS;
			case FLAT -> Items.GRASS_BLOCK;
			case BOX -> Items.BEDROCK;
		};
	}

	private static void report(ServerPlayer p, String problem) {
		if (problem != null) p.sendSystemMessage(Msg.err(problem));
	}

	// ------------------------------------------------------------------ Create Arena

	/** /createarena and the Create Arena button: start from a template, continue the draft or edit an arena. */
	public static void openStudio(ServerPlayer p) {
		if (DuelManager.isBusy(p.getUUID())) {
			p.sendSystemMessage(Msg.err("You are busy: use /leave first."));
			return;
		}
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		if (!BuilderRank.can(p)) {
			b.set(11, DuelMenus.icon(Items.BARRIER, "Builders only", ChatFormatting.RED,
				"Custom arenas are made by players with the Builder rank.", "You get it by placing 1st, 2nd or 3rd in the weekly",
				"building contest, and it lasts " + BuilderRank.DEFAULT_WEEKS + " weeks."));
			b.set(15, DuelMenus.icon(Items.BOOK, "Use arenas made by others", ChatFormatting.AQUA,
				"When you challenge somebody, pick \"Custom arena\"", "in the arena step. /arena public lists the shared ones."));
			ChoiceMenu.open(p, Component.literal("Create Arena"), 3, b);
			return;
		}
		int[] slots = {10, 12, 14};
		ArenaTemplates.Kind[] kinds = ArenaTemplates.Kind.values();
		for (int i = 0; i < kinds.length; i++) {
			ArenaTemplates.Kind kind = kinds[i];
			b.set(slots[i], DuelMenus.icon(iconOf(kind), kind.title, ChatFormatting.GREEN, kind.description, "A starting point: you can change everything.", "Click to start building"), who -> {
				who.closeContainer();
				report(who, ArenaStudio.enter(who, kind, null));
			});
		}
		if (ArenaStore.exists(p.getUUID(), ArenaStudio.DRAFT)) {
			b.set(20, DuelMenus.icon(Items.WRITABLE_BOOK, "Continue my draft", ChatFormatting.YELLOW, "What you were building last time."), who -> {
				who.closeContainer();
				report(who, ArenaStudio.enter(who, null, ArenaStudio.DRAFT));
			});
		}
		if (!ArenaStore.list(p.getUUID()).stream().allMatch(ArenaStudio.DRAFT::equals)) {
			b.set(24, DuelMenus.icon(Items.CHEST, "Edit one of my arenas", ChatFormatting.AQUA, "Open an arena you saved."), who -> openEditList(who, 0));
		}
		ChoiceMenu.open(p, Component.literal("Create Arena"), 3, b);
	}

	private static void openEditList(ServerPlayer p, int page) {
		List<String> names = new ArrayList<>(ArenaStore.list(p.getUUID()));
		names.remove(ArenaStudio.DRAFT);
		int pages = Math.max(1, (names.size() + 44) / 45);
		int current = Math.max(0, Math.min(page, pages - 1));
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < 45; i++) {
			int index = current * 45 + i;
			if (index >= names.size()) break;
			String name = names.get(index);
			b.set(i, DuelMenus.icon(Items.GRASS_BLOCK, name, ChatFormatting.AQUA, "Click to edit this arena"), who -> {
				who.closeContainer();
				report(who, ArenaStudio.enter(who, null, name));
			});
		}
		if (current > 0) b.set(45, DuelMenus.icon(Items.ARROW, "Previous page", ChatFormatting.YELLOW), who -> openEditList(who, current - 1));
		b.set(49, DuelMenus.icon(Items.ARROW, "Back", ChatFormatting.YELLOW), ArenaMenus::openStudio);
		if (current < pages - 1) b.set(53, DuelMenus.icon(Items.ARROW, "Next page", ChatFormatting.YELLOW), who -> openEditList(who, current + 1));
		ChoiceMenu.open(p, Component.literal("Edit an arena (" + (current + 1) + "/" + pages + ")"), 6, b);
	}

	// ------------------------------------------------------------------ choosing an arena for a duel

	/** One arena in the list: the key to store in the duel and the name to show. */
	private record Option(String key, String name, String by, boolean mine) {}

	/** A paged list of the player's arenas and the public ones. onPick gets the key ("player id/name" or "public/name") and the display name. */
	static void pickArena(ServerPlayer p, String title, int page, Runnable back, BiConsumer<ServerPlayer, String[]> onPick) {
		List<Option> options = new ArrayList<>();
		UUID me = p.getUUID();
		for (String name : ArenaStore.list(me)) {
			if (ArenaStudio.DRAFT.equals(name)) continue;
			options.add(new Option(ArenaStore.key(me, name), name, p.getName().getString(), true));
		}
		for (ArenaStore.Entry e : ArenaStore.publicList()) {
			if (me.equals(e.owner())) continue; // already listed as one of mine
			options.add(new Option(ArenaStore.publicKey(e.name()), e.name(), e.ownerName(), false));
		}
		int pages = Math.max(1, (options.size() + 44) / 45);
		int current = Math.max(0, Math.min(page, pages - 1));
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < 45; i++) {
			int index = current * 45 + i;
			if (index >= options.size()) break;
			Option o = options.get(index);
			b.set(i, o.mine()
					? DuelMenus.icon(Items.GRASS_BLOCK, o.name(), ChatFormatting.AQUA, "Your arena", "Click to choose it")
					: DuelMenus.icon(Items.MOSSY_COBBLESTONE, o.name(), ChatFormatting.GOLD, "Public arena" + (o.by().isEmpty() ? "" : " by " + o.by()), "Click to choose it"),
				who -> onPick.accept(who, new String[] {o.key(), o.name()}));
		}
		if (options.isEmpty()) {
			b.set(22, DuelMenus.icon(Items.PAPER, "No custom arenas yet", ChatFormatting.GRAY, "Builders make them with /createarena", "and share them with /arena publish <name>."));
		}
		if (current > 0) b.set(45, DuelMenus.icon(Items.ARROW, "Previous page", ChatFormatting.YELLOW), who -> pickArena(who, title, current - 1, back, onPick));
		b.set(49, DuelMenus.icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> back.run());
		if (current < pages - 1) b.set(53, DuelMenus.icon(Items.ARROW, "Next page", ChatFormatting.YELLOW), who -> pickArena(who, title, current + 1, back, onPick));
		ChoiceMenu.open(p, Component.literal(title + " (" + (current + 1) + "/" + pages + ")"), 6, b);
	}
}
