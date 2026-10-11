package com.pvpduels.duel;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** A chest-shaped menu where clicking an item runs an action and nothing can be taken out. */
final class ChoiceMenu extends ChestMenu {
	private final int rows;
	private final Map<Integer, Consumer<ServerPlayer>> actions;

	private ChoiceMenu(int id, Inventory inv, SimpleContainer container, int rows, Map<Integer, Consumer<ServerPlayer>> actions) {
		super(typeFor(rows), id, inv, container, rows);
		this.rows = rows;
		this.actions = actions;
	}

	private static MenuType<ChestMenu> typeFor(int rows) {
		return switch (rows) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		if (slotId >= 0 && slotId < rows * 9 && player instanceof ServerPlayer sp) {
			Consumer<ServerPlayer> action = actions.get(slotId);
			if (action != null) action.accept(sp);
		}
		if (player.containerMenu == this) {
			setCarried(ItemStack.EMPTY);
			sendAllDataToRemote();
		}
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	/** Collects the items and click actions of one screen. */
	static final class Builder {
		final SimpleContainer container;
		final Map<Integer, Consumer<ServerPlayer>> actions = new HashMap<>();

		Builder(int rows) {
			this.container = new SimpleContainer(rows * 9);
		}

		void set(int slot, ItemStack stack, Consumer<ServerPlayer> onClick) {
			container.setItem(slot, stack);
			if (onClick != null) actions.put(slot, onClick);
		}

		void set(int slot, ItemStack stack) {
			set(slot, stack, null);
		}
	}

	static void open(ServerPlayer player, Component title, int rows, Builder builder) {
		player.openMenu(new SimpleMenuProvider(
			(id, inv, p) -> new ChoiceMenu(id, inv, builder.container, rows, builder.actions), title));
	}
}
