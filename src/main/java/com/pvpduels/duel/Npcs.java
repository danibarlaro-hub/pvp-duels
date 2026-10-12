package com.pvpduels.duel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The NPCs of the lobby: mannequins you click. There are three: the Practice Bot (opens the /botduel menu), the Kit
 * Workshop (opens the kit workshop menu) and the Arena Builder (custom arenas). An admin places them with
 * /npc add bot|kit|arena where they stand, and removes the closest one with /npc remove.
 * <p>
 * An NPC is a vanilla mannequin that cannot be pushed or hurt and carries the scoreboard tag pvpduels_npc_&lt;kind&gt;,
 * so it is saved with the world like any entity and nothing else has to be stored.
 */
public final class Npcs {
	static final String TAG_PREFIX = "pvpduels_npc_";

	enum Kind {
		BOT("bot", "Practice Bot", "Click to practice", "green", "kai"),
		KIT("kit", "Kit Workshop", "Click to create a kit", "aqua", "noor"),
		ARENA("arena", "Arena Builder", "Click to create an arena", "gold", "zuri");

		final String id;
		final String title;
		final String hint;
		final String color;
		final String skin;

		Kind(String id, String title, String hint, String color, String skin) {
			this.id = id;
			this.title = title;
			this.hint = hint;
			this.color = color;
			this.skin = skin;
		}

		String tag() {
			return TAG_PREFIX + id;
		}

		static Kind byId(String id) {
			for (Kind k : values()) if (k.id.equals(id.toLowerCase(Locale.ROOT))) return k;
			return null;
		}
	}

	private Npcs() {}

	/** Which NPC this entity is, or null. */
	static Kind kindOf(Entity entity) {
		if (entity == null || entity.entityTags().isEmpty()) return null;
		for (Kind k : Kind.values()) if (entity.entityTags().contains(k.tag())) return k;
		return null;
	}

	/** A click on an NPC. */
	static void interact(ServerPlayer p, Kind kind) {
		if (DuelManager.isBusy(p.getUUID())) {
			p.sendSystemMessage(Msg.err("You are busy: use /leave first."));
			return;
		}
		switch (kind) {
			case BOT -> DuelMenus.openBot(p, null);
			case KIT -> LobbyKit.openKitMenu(p);
			case ARENA -> LobbyKit.openArenaInfo(p);
		}
	}

	/** The summon command for an NPC of this kind at this place. */
	static String summonCommand(Kind kind, double x, double y, double z, float yaw) {
		String nbt = "{Tags:[\"" + kind.tag() + "\"]"
			+ ",CustomName:{text:\"" + kind.title + "\",color:\"" + kind.color + "\",bold:true}"
			+ ",CustomNameVisible:1b,Invulnerable:1b,Silent:1b,immovable:1b"
			+ ",description:{text:\"" + kind.hint + "\",color:\"gray\"}"
			+ ",profile:{texture:\"entity/player/wide/" + kind.skin + "\"}"
			+ ",Rotation:[" + yaw + "f,0f]}";
		return String.format(Locale.ROOT, "summon minecraft:mannequin %.2f %.2f %.2f %s", x, y, z, nbt);
	}

	/** Places an NPC at the player's feet. Returns the new entity or null when it could not be found after summoning. */
	static Entity place(ServerPlayer admin, Kind kind) {
		return placeAt((ServerLevel) admin.level(), admin.createCommandSourceStack(), kind, admin.getX(), admin.getY(), admin.getZ(), admin.getYRot() + 180f);
	}

	/** Same at any place, with the permission of that command source (summoning needs an admin). */
	static Entity placeAt(ServerLevel level, CommandSourceStack source, Kind kind, double x, double y, double z, float yaw) {
		level.getServer().getCommands().performPrefixedCommand(source.withSuppressedOutput(), summonCommand(kind, x, y, z, yaw));
		return nearest(level, x, y, z, 1.5);
	}

	static List<Entity> around(ServerLevel level, double x, double y, double z, double radius) {
		AABB box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
		return level.getEntities((Entity) null, box, e -> kindOf(e) != null);
	}

	static Entity nearest(ServerLevel level, double x, double y, double z, double radius) {
		Entity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity e : around(level, x, y, z, radius)) {
			double d = e.distanceToSqr(x, y, z);
			if (d < bestDistance) {
				bestDistance = d;
				best = e;
			}
		}
		return best;
	}

	public static void register() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			Kind kind = kindOf(entity);
			if (kind == null) return InteractionResult.PASS;
			interact(sp, kind);
			return InteractionResult.SUCCESS;
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			Kind kind = kindOf(entity);
			if (kind == null) return InteractionResult.PASS;
			// Hitting an NPC works like clicking it. Admins in creative can still remove it with /npc remove.
			interact(sp, kind);
			return InteractionResult.FAIL;
		});
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("npc")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("add")
				.then(Commands.argument("kind", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("bot", "kit", "arena"), builder))
					.executes(ctx -> {
						ServerPlayer p = ctx.getSource().getPlayerOrException();
						Kind kind = Kind.byId(StringArgumentType.getString(ctx, "kind"));
						if (kind == null) {
							ctx.getSource().sendFailure(Msg.err("The NPC can be: bot, kit or arena."));
							return 0;
						}
						Entity npc = place(p, kind);
						if (npc == null) {
							ctx.getSource().sendFailure(Msg.err("The NPC could not be created. Look at the game log."));
							PvpDuelsMod.LOGGER.warn("/npc add {} failed at {}", kind.id, p.blockPosition());
							return 0;
						}
						ctx.getSource().sendSuccess(() -> Msg.ok("Placed the " + kind.title + " NPC. Click it to try it. /npc remove deletes the closest one."), false);
						return 1;
					})))
			.then(Commands.literal("remove").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				Entity npc = nearest((ServerLevel) p.level(), p.getX(), p.getY(), p.getZ(), 6);
				if (npc == null) {
					ctx.getSource().sendFailure(Msg.err("There is no NPC within 6 blocks of you."));
					return 0;
				}
				Kind kind = kindOf(npc);
				npc.discard();
				ctx.getSource().sendSuccess(() -> Msg.ok("Removed the " + kind.title + " NPC."), false);
				return 1;
			}))
			.then(Commands.literal("list").executes(ctx -> {
				List<String> lines = new ArrayList<>();
				for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
					for (Entity e : level.getAllEntities()) {
						Kind kind = kindOf(e);
						if (kind == null) continue;
						lines.add(kind.title + " at " + level.dimension().identifier() + " " + e.blockPosition().getX() + " "
							+ e.blockPosition().getY() + " " + e.blockPosition().getZ());
					}
				}
				if (lines.isEmpty()) {
					ctx.getSource().sendSuccess(() -> Msg.info("There are no NPCs. /npc add bot|kit|arena places one where you stand."), false);
				} else {
					ctx.getSource().sendSuccess(() -> Msg.title("NPCs (" + lines.size() + ")"), false);
					for (String line : lines) ctx.getSource().sendSuccess(() -> Msg.info("  " + line), false);
				}
				return lines.size();
			})));
	}
}
