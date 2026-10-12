package com.pvpduels.workshop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

/**
 * /enchant and /infinite for the kit workshop. They replace Minecraft's /enchant (admins keep the
 * "/enchant &lt;targets&gt; ..." form) because that one is admin-only and refuses most enchantments.
 */
public final class ItemCommands {
	/** Minecraft can't save stacks bigger than this. */
	public static final int MAX_INFINITE = 99;
	public static final int MAX_LEVEL = 255;

	private static final Predicate<CommandSourceStack> IS_ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	private ItemCommands() {}

	/** Normal players may only use these in the workshop; admins can use them anywhere. */
	private static boolean allowed(CommandSourceStack source) {
		return IS_ADMIN.test(source)
			|| (source.getEntity() instanceof ServerPlayer p && Workshop.isIn(p.getUUID()));
	}

	private static boolean refuse(CommandSourceStack source) {
		if (allowed(source)) return false;
		source.sendFailure(Msg.err("You can only use this in the kit workshop. Type /kit create to go there."));
		return true;
	}

	/** Removes a command that Minecraft already has, so ours takes its place. */
	@SuppressWarnings("unchecked")
	public static void removeVanilla(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
		try {
			Object root = dispatcher.getRoot();
			for (String field : new String[] {"children", "literals", "arguments"}) {
				Field f = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField(field);
				f.setAccessible(true);
				((Map<String, ?>) f.get(root)).remove(name);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			com.pvpduels.PvpDuelsMod.LOGGER.warn("Could not replace the vanilla /{} command ({}). Use /ench instead.", name, e.toString());
		}
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
		removeVanilla(dispatcher, "enchant");
		for (String root : new String[] {"enchant", "ench"}) {
			dispatcher.register(Commands.literal(root)
				// /enchant <enchantment> [level]  - the item in your hand
				.then(Commands.argument("enchantment", ResourceArgument.resource(context, Registries.ENCHANTMENT))
					.executes(ctx -> self(ctx, 1))
					.then(Commands.argument("level", IntegerArgumentType.integer(0, MAX_LEVEL))
						.executes(ctx -> self(ctx, IntegerArgumentType.getInteger(ctx, "level")))))
				// /enchant <targets> <enchantment> [level]  - like Minecraft's own command (admins)
				.then(Commands.argument("targets", EntityArgument.entities())
					.then(Commands.argument("enchantment", ResourceArgument.resource(context, Registries.ENCHANTMENT))
						.executes(ctx -> others(ctx, 1))
						.then(Commands.argument("level", IntegerArgumentType.integer(0, MAX_LEVEL))
							.executes(ctx -> others(ctx, IntegerArgumentType.getInteger(ctx, "level")))))));
		}

		dispatcher.register(Commands.literal("infinite")
			.then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_INFINITE))
				.executes(ctx -> infinite(ctx, IntegerArgumentType.getInteger(ctx, "amount")))));
	}

	// ------------------------------------------------------------------ /enchant

	/** Sets the enchantment on the stack, whatever the item and whatever the level (0 removes it). */
	static boolean apply(ItemStack stack, Holder<Enchantment> enchantment, int level) {
		if (stack.isEmpty()) return false;
		EnchantmentHelper.updateEnchantments(stack, mutable -> mutable.set(enchantment, level));
		return true;
	}

	private static int self(CommandContext<CommandSourceStack> ctx, int level) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		if (refuse(source)) return 0;
		ServerPlayer p = source.getPlayerOrException();
		Holder<Enchantment> enchantment = ResourceArgument.getEnchantment(ctx, "enchantment");
		ItemStack stack = p.getMainHandItem();
		if (!apply(stack, enchantment, level)) {
			source.sendFailure(Msg.err("Hold the item you want to enchant in your main hand."));
			return 0;
		}
		p.containerMenu.broadcastChanges();
		p.inventoryMenu.broadcastChanges();
		source.sendSuccess(() -> Msg.ok((level == 0 ? "Removed " : "Applied ") + enchantment.getRegisteredName() + (level == 0 ? "" : " " + level)
			+ " " + (level == 0 ? "from " : "to ") + stack.getHoverName().getString() + "."), false);
		return 1;
	}

	private static int others(CommandContext<CommandSourceStack> ctx, int level) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		if (!IS_ADMIN.test(source)) {
			source.sendFailure(Msg.err("Only admins can enchant other players. Use /enchant <enchantment> [level] for yourself."));
			return 0;
		}
		Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "targets");
		Holder<Enchantment> enchantment = ResourceArgument.getEnchantment(ctx, "enchantment");
		int done = 0;
		for (Entity e : targets) {
			if (e instanceof LivingEntity living && apply(living.getMainHandItem(), enchantment, level)) {
				done++;
				if (living instanceof ServerPlayer sp) sp.containerMenu.broadcastChanges();
			}
		}
		if (done == 0) {
			source.sendFailure(Msg.err("None of the targets is holding an item."));
			return 0;
		}
		int count = done;
		source.sendSuccess(() -> Msg.ok("Enchanted the item of " + count + " target" + (count == 1 ? "" : "s") + "."), true);
		return done;
	}

	// ------------------------------------------------------------------ /infinite

	private static int infinite(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		if (refuse(source)) return 0;
		ServerPlayer p = source.getPlayerOrException();
		ItemStack stack = p.getMainHandItem();
		if (stack.isEmpty()) {
			source.sendFailure(Msg.err("Hold the item you want to stack in your main hand."));
			return 0;
		}
		if (stack.isDamageableItem()) {
			source.sendFailure(Msg.err("Items with durability can't be stacked. Try totems, potions, pearls, food..."));
			return 0;
		}
		int natural = stack.getItem().getDefaultMaxStackSize();
		if (amount > natural) stack.set(DataComponents.MAX_STACK_SIZE, amount);
		else stack.remove(DataComponents.MAX_STACK_SIZE);
		stack.setCount(amount);
		p.containerMenu.broadcastChanges();
		p.inventoryMenu.broadcastChanges();
		source.sendSuccess(() -> Msg.ok("You now have a stack of " + amount + " " + stack.getHoverName().getString() + "."), false);
		return 1;
	}
}
