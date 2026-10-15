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
		openTitles(p, 0);
	}

	/** The titles: first the ones you won (click to wear), then the locked ones with how far you are from each. 45 to a page. */
	public static void openTitles(ServerPlayer p, int page) {
		UUID me = p.getUUID();
		List<Achievement> won = Achievements.titlesOf(me);
		List<Achievement> locked = Achievements.lockedTitlesOf(me);
		int total = won.size() + locked.size();
		int pages = Math.max(1, (total + 44) / 45);
		int current = Math.max(0, Math.min(page, pages - 1));
		Achievement worn = Achievements.titleOf(me);
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < 45; i++) {
			int index = current * 45 + i;
			if (index >= total) break;
			if (index < won.size()) {
				Achievement a = won.get(index);
				boolean on = worn != null && worn.id().equals(a.id());
				b.set(i, DuelMenus.icon(on ? Items.ENCHANTED_BOOK : Items.NAME_TAG, "[" + a.title() + "]", a.color(), "Unlocked with: " + a.name(),
					on ? "You are wearing it" : "Click to wear it"), who -> {
					who.closeContainer();
					String problem = Achievements.setTitle(who, a.id());
					who.sendSystemMessage(problem != null ? Msg.err(problem) : Msg.ok("You wear the title [" + a.title() + "] now."));
				});
			} else {
				Achievement a = locked.get(index - won.size());
				long value = Math.min(Achievements.counter(me, a.counter()), a.target());
				b.set(i, DuelMenus.icon(Items.COAL, "Locked: [" + a.title() + "]", ChatFormatting.DARK_GRAY, "Win it with: " + a.name(), a.description(), "Progress: " + value + "/" + a.target()));
			}
		}
		b.set(46, DuelMenus.icon(Items.ARROW, "Achievements", ChatFormatting.YELLOW), who -> openList(who, 0));
		b.set(47, DuelMenus.icon(Items.BOOK, "Titles: " + won.size() + "/" + total, ChatFormatting.GOLD, won.isEmpty() ? "You have none yet: win achievements to get them." : "Click one you won to wear it."));
		b.set(49, DuelMenus.icon(Items.BARRIER, "No title", ChatFormatting.RED, "Take the title off."), who -> {
			who.closeContainer();
			Achievements.setTitle(who, "");
			who.sendSystemMessage(Msg.ok("You don't wear a title now."));
		});
		if (current > 0) b.set(45, DuelMenus.icon(Items.ARROW, "Previous page", ChatFormatting.YELLOW), who -> openTitles(who, current - 1));
		if (current < pages - 1) b.set(53, DuelMenus.icon(Items.ARROW, "Next page", ChatFormatting.YELLOW), who -> openTitles(who, current + 1));
		ChoiceMenu.open(p, Component.literal("My titles (" + (current + 1) + "/" + pages + ")"), 6, b);
	}
}
