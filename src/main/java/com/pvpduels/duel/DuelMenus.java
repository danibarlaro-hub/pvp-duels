package com.pvpduels.duel;

import com.pvpduels.kit.KitStore;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The chest menus of /duel: kit, arena, version, win mode, amount, confirm. All text is English. */
public final class DuelMenus {
	private static final int[] ROUND_OPTIONS = {1, 2, 3, 5, 7, 10, 15, 20, 25, 50, 100};
	private static final int[] MINUTE_OPTIONS = {3, 5, 10, 15, 20, 30};

	private DuelMenus() {}

	/** What the challenger has picked so far. */
	private static final class Setup {
		final UUID opponent;
		final String opponentName;
		String kit;
		ArenaKind arena = ArenaKind.FLAT;
		int radius = 100;
		boolean walls;
		WinMode mode = WinMode.ROUNDS;
		int limit = 3;

		Setup(ServerPlayer opponent) {
			this.opponent = opponent.getUUID();
			this.opponentName = opponent.getName().getString();
		}
	}

	private static ItemStack icon(Item item, String name, ChatFormatting color, String... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withColor(color).withItalic(false)));
		if (lore.length > 0) {
			List<Component> lines = new ArrayList<>();
			for (String line : lore) {
				lines.add(Component.literal(line).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)));
			}
			stack.set(DataComponents.LORE, new ItemLore(lines));
		}
		return stack;
	}

	private static ItemStack withName(ItemStack base, String name, ChatFormatting color, String... lore) {
		ItemStack stack = base.copy();
		stack.setCount(1);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withColor(color).withItalic(false)));
		List<Component> lines = new ArrayList<>();
		for (String line : lore) {
			lines.add(Component.literal(line).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)));
		}
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return stack;
	}

	// ------------------------------------------------------------------ entry point

	public static void open(ServerPlayer challenger, ServerPlayer opponent) {
		if (KitStore.list(challenger.getUUID()).isEmpty()) {
			challenger.sendSystemMessage(Msg.err("You have no kits yet. Set up your inventory and run /kit save <name> first."));
			return;
		}
		kits(challenger, new Setup(opponent), 0);
	}

	// ------------------------------------------------------------------ 1. kit

	private static ItemStack kitIcon(MinecraftServer server, UUID owner, String name) {
		try {
			Optional<KitStore.Kit> kit = KitStore.read(server, owner, name);
			if (kit.isPresent()) {
				ItemStack[] slots = kit.get().slots();
				for (int i = 0; i < 36; i++) {
					if (!slots[i].isEmpty()) return withName(slots[i], name, ChatFormatting.AQUA, "Click to choose this kit");
				}
				for (int i = 36; i < slots.length; i++) {
					if (!slots[i].isEmpty()) return withName(slots[i], name, ChatFormatting.AQUA, "Click to choose this kit");
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// fall through to the plain chest
		}
		return icon(Items.CHEST, name, ChatFormatting.AQUA, "Click to choose this kit");
	}

	private static void kits(ServerPlayer p, Setup setup, int page) {
		MinecraftServer server = p.level().getServer();
		List<String> names = KitStore.list(p.getUUID());
		int pages = Math.max(1, (names.size() + 44) / 45);
		int current = Math.max(0, Math.min(page, pages - 1));

		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < 45; i++) {
			int index = current * 45 + i;
			if (index >= names.size()) break;
			String name = names.get(index);
			b.set(i, kitIcon(server, p.getUUID(), name), who -> {
				setup.kit = name;
				arena(who, setup);
			});
		}
		if (current > 0) b.set(45, icon(Items.ARROW, "Previous page", ChatFormatting.YELLOW), who -> kits(who, setup, current - 1));
		b.set(49, icon(Items.BARRIER, "Cancel", ChatFormatting.RED), ServerPlayer::closeContainer);
		if (current < pages - 1) b.set(53, icon(Items.ARROW, "Next page", ChatFormatting.YELLOW), who -> kits(who, setup, current + 1));
		ChoiceMenu.open(p, Component.literal("Duel vs " + setup.opponentName + ": choose a kit (" + (current + 1) + "/" + pages + ")"), 6, b);
	}

	// ------------------------------------------------------------------ 2. arena

	private static void arena(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(11, icon(Items.END_CRYSTAL, "Flat void arena", ChatFormatting.GREEN,
			"A 21x21 bedrock platform over the void.", "You can only place entities and block entities.", "Falling off loses the round."),
			who -> {
				setup.arena = ArenaKind.FLAT;
				version(who, setup);
			});
		b.set(13, icon(Items.GRASS_BLOCK, "Circular arena", ChatFormatting.GREEN,
			"Flat normal terrain inside a circle of invisible barriers.", "You can build and dig anywhere.",
			"The terrain is reset after every round.", "Leaving the circle loses the round."),
			who -> {
				setup.arena = ArenaKind.CIRCLE;
				radius(who, setup);
			});
		b.set(15, icon(Items.ELYTRA, "Unlimited arena", ChatFormatting.GREEN,
			"Flat normal terrain with no border.", "You can build and dig anywhere.",
			"If you stay more than 100 blocks away from your rival for 5 seconds,",
			"you lose half of your health and get teleported to them."),
			who -> {
				setup.arena = ArenaKind.UNLIMITED;
				mode(who, setup);
			});
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> kits(who, setup, 0));
		ChoiceMenu.open(p, Component.literal("Duel: choose an arena"), 3, b);
	}

	// ------------------------------------------------------------------ 2b. circle radius

	private static void radius(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		int[] options = {100, 150, 200};
		int slot = 11;
		for (int value : options) {
			b.set(slot, icon(Items.GRASS_BLOCK, "Radius " + value, ChatFormatting.GREEN,
				"The circle is " + (value * 2) + " blocks wide."), who -> {
				setup.radius = value;
				mode(who, setup);
			});
			slot += 2;
		}
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> arena(who, setup));
		ChoiceMenu.open(p, Component.literal("Duel: how big is the circle?"), 3, b);
	}

	/** The "Back" button of the win-mode menu returns to the last menu of the chosen arena. */
	private static void backToArena(ServerPlayer p, Setup setup) {
		switch (setup.arena) {
			case FLAT -> version(p, setup);
			case CIRCLE -> radius(p, setup);
			case UNLIMITED -> arena(p, setup);
		}
	}

	// ------------------------------------------------------------------ 3. version

	private static void version(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(11, icon(Items.BEDROCK, "No walls", ChatFormatting.GREEN, "Knockback can push you off the edge."), who -> {
			setup.walls = false;
			mode(who, setup);
		});
		b.set(15, icon(Items.OBSIDIAN, "With walls", ChatFormatting.GREEN, "Bedrock walls around the platform."), who -> {
			setup.walls = true;
			mode(who, setup);
		});
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> arena(who, setup));
		ChoiceMenu.open(p, Component.literal("Duel: choose a version"), 3, b);
	}

	// ------------------------------------------------------------------ 4. win mode

	private static void mode(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(10, icon(Items.PAPER, "Fixed rounds", ChatFormatting.GREEN,
			"Play a set number of rounds.", "Most points at the end wins (ties play an extra round).", "Pick \"Unlimited\" to play until someone uses /leave."),
			who -> {
				setup.mode = WinMode.ROUNDS;
				amount(who, setup);
			});
		b.set(13, icon(Items.NETHER_STAR, "First to N points", ChatFormatting.GREEN,
			"The first player to reach N points wins.", "Pick \"Unlimited\" to play until someone uses /leave."),
			who -> {
				setup.mode = WinMode.FIRST_TO;
				amount(who, setup);
			});
		b.set(16, icon(Items.CLOCK, "Timed", ChatFormatting.GREEN,
			"Play until the time runs out (max 30 minutes).", "The running round is finished, then most points wins."),
			who -> {
				setup.mode = WinMode.TIMED;
				amount(who, setup);
			});
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> backToArena(who, setup));
		ChoiceMenu.open(p, Component.literal("Duel: how is it decided?"), 3, b);
	}

	// ------------------------------------------------------------------ 5. amount

	private static void amount(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(4);
		int[] options = setup.mode == WinMode.TIMED ? MINUTE_OPTIONS : ROUND_OPTIONS;
		int slot = 9;
		for (int value : options) {
			String label = switch (setup.mode) {
				case ROUNDS -> value + (value == 1 ? " round" : " rounds");
				case FIRST_TO -> "First to " + value;
				case TIMED -> value + " minutes";
			};
			ItemStack stack = icon(Items.PAPER, label, ChatFormatting.YELLOW);
			stack.setCount(Math.min(value, 64));
			b.set(slot, stack, who -> {
				setup.limit = value;
				confirm(who, setup);
			});
			slot++;
		}
		if (setup.mode != WinMode.TIMED) {
			b.set(22, icon(Items.BOOK, "Unlimited", ChatFormatting.LIGHT_PURPLE, "No limit: the duel lasts until someone uses /leave.",
				"Only a small score is shown on screen."), who -> {
				setup.limit = 0;
				confirm(who, setup);
			});
		}
		b.set(27, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> mode(who, setup));
		String what = switch (setup.mode) {
			case ROUNDS -> "how many rounds?";
			case FIRST_TO -> "how many points?";
			case TIMED -> "how long?";
		};
		ChoiceMenu.open(p, Component.literal("Duel: " + what), 4, b);
	}

	// ------------------------------------------------------------------ 6. confirm

	private static DuelConfig config(Setup setup) {
		return new DuelConfig(setup.kit, setup.arena, setup.walls, setup.arena == ArenaKind.CIRCLE ? setup.radius : 0, setup.mode, setup.limit);
	}

	private static void confirm(ServerPlayer p, Setup setup) {
		DuelConfig config = config(setup);
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(13, icon(Items.BOOK, "Duel vs " + setup.opponentName, ChatFormatting.GOLD,
			"Kit: " + config.kit(), "Arena: " + config.arenaName(), "Mode: " + config.modeName()));
		b.set(11, icon(Items.EMERALD, "Send the challenge", ChatFormatting.GREEN), who -> {
			who.closeContainer();
			ServerPlayer target = who.level().getServer().getPlayerList().getPlayer(setup.opponent);
			if (target == null) {
				who.sendSystemMessage(Msg.err(setup.opponentName + " is no longer online."));
				return;
			}
			DuelManager.sendInvite(who, target, who.getUUID(), config, false);
		});
		b.set(15, icon(Items.BARRIER, "Cancel", ChatFormatting.RED), ServerPlayer::closeContainer);
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> amount(who, setup));
		ChoiceMenu.open(p, Component.literal("Duel: confirm"), 3, b);
	}
}
