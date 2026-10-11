package com.pvpduels.layout;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class LayoutCommands {
	private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,24}");

	private static final SuggestionProvider<CommandSourceStack> PROFILES = (ctx, builder) -> {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return builder.buildFuture();
		return SharedSuggestionProvider.suggest(LayoutStore.orderedNames(p.getUUID()), builder);
	};

	private static final SuggestionProvider<CommandSourceStack> CLEAR_TARGETS = (ctx, builder) ->
		SharedSuggestionProvider.suggest(List.of("all"), builder);

	private LayoutCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("layout")
			.then(Commands.literal("slot")
				.then(Commands.argument("slot", StringArgumentType.word())
					.then(Commands.argument("rules", StringArgumentType.greedyString())
						.executes(LayoutCommands::setSlot))))
			.then(Commands.literal("level")
				.then(Commands.argument("slot", StringArgumentType.word())
					.then(Commands.argument("level", IntegerArgumentType.integer(1, LayoutStore.MAX_LEVELS))
						.then(Commands.argument("rule", StringArgumentType.word())
							.executes(LayoutCommands::setLevel)))))
			.then(Commands.literal("clear")
				.then(Commands.argument("target", StringArgumentType.word()).suggests(CLEAR_TARGETS)
					.executes(LayoutCommands::clear)))
			.then(Commands.literal("show").executes(LayoutCommands::show))
			.then(Commands.literal("list").executes(LayoutCommands::list))
			.then(Commands.literal("new")
				.then(Commands.argument("name", StringArgumentType.word()).executes(LayoutCommands::newProfile)))
			.then(Commands.literal("use")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(PROFILES).executes(LayoutCommands::use)))
			.then(Commands.literal("copy")
				.then(Commands.argument("from", StringArgumentType.word()).suggests(PROFILES)
					.then(Commands.argument("to", StringArgumentType.word()).executes(LayoutCommands::copy))))
			.then(Commands.literal("delete")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(PROFILES).executes(LayoutCommands::delete)))
			.then(Commands.literal("key")
				.then(Commands.argument("key", IntegerArgumentType.integer(1, LayoutStore.QUICK_KEYS))
					.then(Commands.argument("layout", StringArgumentType.word()).suggests(PROFILES)
						.executes(LayoutCommands::setKey))))
			.then(Commands.literal("keys").executes(LayoutCommands::showKeys))
			.then(Commands.literal("sort").executes(ctx -> {
				InventorySorter.sortAndReport(ctx.getSource().getPlayerOrException());
				return 1;
			})));
	}

	/** Called by the "Next layout" / "Previous layout" keys: switches the active layout and sorts with it. */
	public static void cycle(ServerPlayer p, boolean forward) {
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		List<String> names = LayoutStore.orderedNames(p.getUUID());
		int idx = names.indexOf(data.active);
		if (idx < 0) idx = 0;
		int next = (idx + (forward ? 1 : -1) + names.size()) % names.size();
		data.active = names.get(next);
		try {
			LayoutStore.save(p.getUUID());
		} catch (IOException e) {
			p.sendSystemMessage(Msg.err("Could not save your layout file: " + e.getMessage()));
		}
		InventorySorter.sortAndReport(p);
	}

	/** Called by the 3 "Quick layout" keys: switches to the layout bound to that key and sorts with it. */
	public static void quick(ServerPlayer p, int index) {
		if (index < 0 || index >= LayoutStore.QUICK_KEYS) return;
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		String name = data.keys.get(index);
		if (!data.profiles.containsKey(name)) {
			Integer n = LayoutStore.asNumber(name);
			boolean numbered = n != null && n >= 1 && n <= LayoutStore.MAX_PROFILES;
			if (numbered && LayoutStore.customCount(data) < LayoutStore.MAX_PROFILES) {
				data.profiles.put(name, new LayoutStore.Profile());
			} else {
				p.sendSystemMessage(Msg.err("Quick layout key " + (index + 1) + " points to '" + name
					+ "', which doesn't exist. Use /layout key " + (index + 1) + " <layout>."));
				return;
			}
		}
		data.active = name;
		try {
			LayoutStore.save(p.getUUID());
		} catch (IOException e) {
			p.sendSystemMessage(Msg.err("Could not save your layout file: " + e.getMessage()));
		}
		InventorySorter.sortAndReport(p);
	}

	// ---------- helpers ----------

	private static int parseSlot(String s) {
		try {
			int n = Integer.parseInt(s);
			if (n >= 1 && n <= LayoutStore.MAX_SLOT) return n;
		} catch (NumberFormatException ignored) {
			// fall through
		}
		return -1;
	}

	private static String slotName(int slot) {
		if (slot <= 9) return "hotbar " + slot;
		return "inventory " + slot;
	}

	private static String parseRule(ServerPlayer p, String token, CommandContext<CommandSourceStack> ctx) {
		if (token.equals("hand")) {
			ItemStack held = p.getMainHandItem();
			if (held.isEmpty()) {
				ctx.getSource().sendFailure(Msg.err("Your hand is empty, so 'hand' can't be used."));
				return null;
			}
			return BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
		}
		String rule = ItemMatcher.normalize(token);
		if (rule == null) {
			ctx.getSource().sendFailure(Msg.err("Unknown item or category: " + token
				+ ". Categories: " + String.join(", ", ItemMatcher.CATEGORIES.stream().sorted().toList())));
		}
		return rule;
	}

	private static void saveQuietly(ServerPlayer p, CommandContext<CommandSourceStack> ctx) {
		try {
			LayoutStore.save(p.getUUID());
		} catch (IOException e) {
			ctx.getSource().sendFailure(Msg.err("Could not save your layout file: " + e.getMessage()));
		}
	}

	private static int badSlot(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendFailure(Msg.err("Slot must be 1-" + LayoutStore.MAX_SLOT
			+ " (1-9 hotbar, 10-36 backpack)."));
		return 0;
	}

	// ---------- commands ----------

	private static int setSlot(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		int slot = parseSlot(StringArgumentType.getString(ctx, "slot"));
		if (slot < 0) return badSlot(ctx);

		String raw = StringArgumentType.getString(ctx, "rules").trim().toLowerCase(Locale.ROOT);
		String[] tokens = raw.split("\\s+");
		if (tokens.length > LayoutStore.MAX_LEVELS) {
			ctx.getSource().sendFailure(Msg.err("A slot can have at most " + LayoutStore.MAX_LEVELS + " priority levels."));
			return 0;
		}

		List<String> rules = new ArrayList<>();
		for (String token : tokens) {
			String rule = parseRule(p, token, ctx);
			if (rule == null) return 0;
			if (!rules.contains(rule)) rules.add(rule);
		}

		LayoutStore.active(p.getUUID()).slots.put(slot, rules);
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Slot " + slotName(slot) + ": " + String.join(" > ", rules)), false);
		return 1;
	}

	private static int setLevel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		int slot = parseSlot(StringArgumentType.getString(ctx, "slot"));
		if (slot < 0) return badSlot(ctx);
		int level = IntegerArgumentType.getInteger(ctx, "level");
		String token = StringArgumentType.getString(ctx, "rule").toLowerCase(Locale.ROOT);

		Map<Integer, List<String>> slots = LayoutStore.active(p.getUUID()).slots;
		List<String> rules = new ArrayList<>(slots.getOrDefault(slot, List.of()));

		if (token.equals("none")) {
			if (level > rules.size()) {
				ctx.getSource().sendFailure(Msg.err("Level " + level + " of " + slotName(slot) + " is already empty."));
				return 0;
			}
			rules.remove(level - 1);
		} else {
			String rule = parseRule(p, token, ctx);
			if (rule == null) return 0;
			if (level > rules.size() + 1) {
				ctx.getSource().sendFailure(Msg.err("Fill level " + (rules.size() + 1) + " first."));
				return 0;
			}
			if (level == rules.size() + 1) {
				rules.add(rule);
			} else {
				rules.set(level - 1, rule);
			}
		}

		if (rules.isEmpty()) {
			slots.remove(slot);
		} else {
			slots.put(slot, rules);
		}
		saveQuietly(p, ctx);
		String text = rules.isEmpty() ? "(empty)" : String.join(" > ", rules);
		ctx.getSource().sendSuccess(() -> Msg.ok("Slot " + slotName(slot) + ": " + text), false);
		return 1;
	}

	private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String target = StringArgumentType.getString(ctx, "target").toLowerCase(Locale.ROOT);
		LayoutStore.Profile profile = LayoutStore.active(p.getUUID());
		if (target.equals("all")) {
			profile.slots.clear();
			saveQuietly(p, ctx);
			ctx.getSource().sendSuccess(() -> Msg.ok("Cleared every slot of the active layout."), false);
			return 1;
		}
		int slot = parseSlot(target);
		if (slot < 0) return badSlot(ctx);
		profile.slots.remove(slot);
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Cleared slot " + slotName(slot) + "."), false);
		return 1;
	}

	private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		LayoutStore.Profile profile = data.profiles.get(data.active);
		ctx.getSource().sendSuccess(() -> Msg.title("Layout: " + data.active), false);
		boolean any = false;
		List<Integer> order = new ArrayList<>();
		for (int s = 1; s <= LayoutStore.MAX_SLOT; s++) order.add(s);
		for (int slot : order) {
			List<String> rules = profile.slots.get(slot);
			if (rules != null && !rules.isEmpty()) {
				any = true;
				String line = "  " + slotName(slot) + ": " + String.join(" > ", rules);
				ctx.getSource().sendSuccess(() -> Msg.info(line), false);
			}
		}
		if (!any) {
			ctx.getSource().sendSuccess(() -> Msg.info("  (no rules yet)  Try: /layout slot 1 sword spear"), false);
		}
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		StringBuilder sb = new StringBuilder();
		for (String name : LayoutStore.orderedNames(p.getUUID())) {
			if (sb.length() > 0) sb.append(", ");
			int filled = (int) data.profiles.get(name).slots.values().stream().filter(l -> !l.isEmpty()).count();
			sb.append(name).append(" [").append(filled).append(" slots]");
			if (name.equals(data.active)) sb.append(" (active)");
		}
		String text = sb.toString();
		int used = LayoutStore.customCount(data);
		ctx.getSource().sendSuccess(() -> Msg.info("Layouts (" + used + "/" + LayoutStore.MAX_PROFILES + "): " + text), false);
		return 1;
	}

	private static int newProfile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
		if (!NAME.matcher(name).matches()) {
			ctx.getSource().sendFailure(Msg.err("Layout names can only use a-z, 0-9, _ and - (max 24)."));
			return 0;
		}
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		if (data.profiles.containsKey(name)) {
			ctx.getSource().sendFailure(Msg.err("A layout named '" + name + "' already exists."));
			return 0;
		}
		if (LayoutStore.customCount(data) >= LayoutStore.MAX_PROFILES) {
			ctx.getSource().sendFailure(Msg.err("You already have " + LayoutStore.MAX_PROFILES + " layouts (the maximum). Delete one first."));
			return 0;
		}
		data.profiles.put(name, new LayoutStore.Profile());
		data.active = name;
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Created layout '" + name + "' and made it active."), false);
		return 1;
	}

	private static int use(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		if (!data.profiles.containsKey(name)) {
			Integer n = LayoutStore.asNumber(name);
			boolean numbered = n != null && n >= 1 && n <= LayoutStore.MAX_PROFILES;
			if (numbered && LayoutStore.customCount(data) < LayoutStore.MAX_PROFILES) {
				data.profiles.put(name, new LayoutStore.Profile());
				ctx.getSource().sendSuccess(() -> Msg.info("Created new empty layout '" + name + "'."), false);
			} else {
				ctx.getSource().sendFailure(Msg.err("No layout named '" + name + "'. Use /layout list."));
				return 0;
			}
		}
		data.active = name;
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Active layout: " + name), false);
		return 1;
	}

	private static int copy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String from = StringArgumentType.getString(ctx, "from").toLowerCase(Locale.ROOT);
		String to = StringArgumentType.getString(ctx, "to").toLowerCase(Locale.ROOT);
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		LayoutStore.Profile source = data.profiles.get(from);
		if (source == null) {
			ctx.getSource().sendFailure(Msg.err("No layout named '" + from + "'."));
			return 0;
		}
		if (!NAME.matcher(to).matches()) {
			ctx.getSource().sendFailure(Msg.err("Layout names can only use a-z, 0-9, _ and - (max 24)."));
			return 0;
		}
		if (from.equals(to)) {
			ctx.getSource().sendFailure(Msg.err("Pick a different name for the copy."));
			return 0;
		}
		boolean existed = data.profiles.containsKey(to);
		if (!existed && LayoutStore.customCount(data) >= LayoutStore.MAX_PROFILES) {
			ctx.getSource().sendFailure(Msg.err("You already have " + LayoutStore.MAX_PROFILES + " layouts (the maximum). Delete one first."));
			return 0;
		}
		LayoutStore.Profile copy = new LayoutStore.Profile();
		for (Map.Entry<Integer, List<String>> e : source.slots.entrySet()) {
			copy.slots.put(e.getKey(), new ArrayList<>(e.getValue()));
		}
		data.profiles.put(to, copy);
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok((existed ? "Overwrote layout '" : "Copied into new layout '") + to + "' from '" + from + "'."), false);
		return 1;
	}

	private static int setKey(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		int key = IntegerArgumentType.getInteger(ctx, "key");
		String name = StringArgumentType.getString(ctx, "layout").toLowerCase(Locale.ROOT);
		if (!NAME.matcher(name).matches()) {
			ctx.getSource().sendFailure(Msg.err("Layout names can only use a-z, 0-9, _ and - (max 24)."));
			return 0;
		}
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		data.keys.set(key - 1, name);
		saveQuietly(p, ctx);
		boolean exists = data.profiles.containsKey(name);
		ctx.getSource().sendSuccess(() -> Msg.ok("Quick layout key " + key + " now uses layout '" + name + "'."
			+ (exists ? "" : " (It doesn't exist yet - create it with /layout new " + name + ".)")), false);
		return 1;
	}

	private static int showKeys(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		ctx.getSource().sendSuccess(() -> Msg.title("Quick layout keys"), false);
		for (int i = 0; i < LayoutStore.QUICK_KEYS; i++) {
			String line = "  Quick layout " + (i + 1) + " -> '" + data.keys.get(i) + "'";
			ctx.getSource().sendSuccess(() -> Msg.info(line), false);
		}
		ctx.getSource().sendSuccess(() -> Msg.info("Bind the keys in Options > Controls > Key Binds > PvP Duels. Change what they use with /layout key <1-3> <layout>."), false);
		return 1;
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		if (name.equals("default")) {
			ctx.getSource().sendFailure(Msg.err("The 'default' layout can't be deleted (use /layout clear all to empty it)."));
			return 0;
		}
		if (data.profiles.remove(name) == null) {
			ctx.getSource().sendFailure(Msg.err("No layout named '" + name + "'."));
			return 0;
		}
		if (data.active.equals(name)) data.active = "default";
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Deleted layout '" + name + "'."), false);
		return 1;
	}
}
