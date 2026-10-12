package com.pvpduels.duel;

import com.pvpduels.arena.ArenaStudio;
import com.pvpduels.arena.Contest;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/** The gallery of the building contest: a chest menu with one item per build, without the names of the builders. */
public final class ContestMenus {
	private ContestMenus() {}

	public static void openGallery(ServerPlayer p, int page) {
		if (!Contest.voting()) {
			p.sendSystemMessage(Msg.err("The voting is not open. /contest tells you what is going on."));
			return;
		}
		List<UUID> builds = Contest.gallery();
		UUID voted = Contest.votedFor(p.getUUID());
		int pages = Math.max(1, (builds.size() + 44) / 45);
		int current = Math.max(0, Math.min(page, pages - 1));
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < 45; i++) {
			int index = current * 45 + i;
			if (index >= builds.size()) break;
			UUID owner = builds.get(index);
			int number = index + 1;
			ItemStack icon;
			if (owner.equals(p.getUUID())) {
				icon = DuelMenus.icon(Items.NAME_TAG, "Build #" + number + " (yours)", ChatFormatting.AQUA, "You can't vote for your own build.", "Click to visit it");
			} else if (owner.equals(voted)) {
				icon = DuelMenus.icon(Items.EMERALD, "Build #" + number, ChatFormatting.GREEN, "You voted for this one.", "Click to visit it");
			} else {
				icon = DuelMenus.icon(Items.PAINTING, "Build #" + number, ChatFormatting.YELLOW, "Click to visit it and vote.");
			}
			b.set(i, icon, who -> {
				who.closeContainer();
				String problem = ArenaStudio.visit(who, owner);
				if (problem != null) who.sendSystemMessage(Msg.err(problem));
			});
		}
		if (builds.isEmpty()) b.set(22, DuelMenus.icon(Items.PAPER, "No builds", ChatFormatting.GRAY));
		if (current > 0) b.set(45, DuelMenus.icon(Items.ARROW, "Previous page", ChatFormatting.YELLOW), who -> openGallery(who, current - 1));
		b.set(49, DuelMenus.icon(Items.BARRIER, "Close", ChatFormatting.RED), ServerPlayer::closeContainer);
		if (current < pages - 1) b.set(53, DuelMenus.icon(Items.ARROW, "Next page", ChatFormatting.YELLOW), who -> openGallery(who, current + 1));
		ChoiceMenu.open(p, Component.literal("Vote: " + Contest.category() + " (" + (current + 1) + "/" + pages + ")"), 6, b);
	}
}
