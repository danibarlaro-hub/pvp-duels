package com.pvpduels.duel;

import com.pvpduels.achievements.Achievement;
import com.pvpduels.achievements.Achievements;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/** The chest menus of the achievements: the list of all of them and the titles you can wear. */
public final class AchievementMenus {
	private AchievementMenus() {}

	public static void openList(ServerPlayer p, int page) {
		UUID me = p.getUUID();
		List<Achievement> all = Achievements.all();
		int pages = Math.max(1, (all.size() + 44) / 45);
		int current = Math.max(0, Math.min(page, pages - 1));
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < 45; i++) {
			int index = current * 45 + i;
			if (index >= all.size()) break;
			Achievement a = all.get(index);
			ItemStack icon;
			if (Achievements.has(me, a.id())) {
				icon = a.hasTitle()
					? DuelMenus.icon(a.icon(), a.name(), ChatFormatting.GREEN, a.description(), "Unlocked!", "Title: [" + a.title() + "]")
					: DuelMenus.icon(a.icon(), a.name(), ChatFormatting.GREEN, a.description(), "Unlocked!");
			} else {
				long value = Math.min(Achievements.counter(me, a.counter()), a.target());
				icon = DuelMenus.icon(Items.COAL, a.name(), ChatFormatting.GRAY, a.description(), "Progress: " + value + "/" + a.target(),
					a.hasTitle() ? "Gives the title [" + a.title() + "]" : "");
			}
			b.set(i, icon);
		}
		if (current > 0) b.set(45, DuelMenus.icon(Items.ARROW, "Previous page", ChatFormatting.YELLOW), who -> openList(who, current - 1));
		b.set(48, DuelMenus.icon(Items.NAME_TAG, "My titles", ChatFormatting.GOLD, "Pick the title in front of your name."), AchievementMenus::openTitles);
		b.set(50, DuelMenus.icon(Items.BARRIER, "Close", ChatFormatting.RED), ServerPlayer::closeContainer);
		if (current < pages - 1) b.set(53, DuelMenus.icon(Items.ARROW, "Next page", ChatFormatting.YELLOW), who -> openList(who, current + 1));
		ChoiceMenu.open(p, Component.literal("Achievements " + Achievements.unlockedCount(me) + "/" + all.size() + " (" + (current + 1) + "/" + pages + ")"), 6, b);
	}

	public static void openTitles(ServerPlayer p) {
		UUID me = p.getUUID();
		List<Achievement> titles = Achievements.titlesOf(me);
		Achievement worn = Achievements.titleOf(me);
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < titles.size() && i < 45; i++) {
			Achievement a = titles.get(i);
			boolean on = worn != null && worn.id().equals(a.id());
			b.set(i, DuelMenus.icon(on ? Items.ENCHANTED_BOOK : Items.NAME_TAG, "[" + a.title() + "]", a.color(), "Unlocked with: " + a.name(),
				on ? "You are wearing it" : "Click to wear it"), who -> {
				who.closeContainer();
				String problem = Achievements.setTitle(who, a.id());
				who.sendSystemMessage(problem != null ? Msg.err(problem) : Msg.ok("You wear the title [" + a.title() + "] now."));
			});
		}
		if (titles.isEmpty()) {
			b.set(22, DuelMenus.icon(Items.PAPER, "No titles yet", ChatFormatting.GRAY, "Unlock achievements to get titles:", "Rookie, Duelist, Champion, Builder..."));
		}
		b.set(45, DuelMenus.icon(Items.ARROW, "Achievements", ChatFormatting.YELLOW), who -> openList(who, 0));
		b.set(49, DuelMenus.icon(Items.BARRIER, "No title", ChatFormatting.RED, "Take the title off."), who -> {
			who.closeContainer();
			Achievements.setTitle(who, "");
			who.sendSystemMessage(Msg.ok("You don't wear a title now."));
		});
		ChoiceMenu.open(p, Component.literal("My titles"), 6, b);
	}
}
