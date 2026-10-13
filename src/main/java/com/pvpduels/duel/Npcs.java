package com.pvpduels.duel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.arena.BuilderRank;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
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
 * The NPCs of the lobby: mannequins you click. There are four: the Practice Bot (opens the /botduel menu), the Kit
 * Workshop (opens the kit workshop menu), the Arena Builder (custom arenas) and the Explosion Dummy (you try your
 * explosions on it). They appear by themselves in front of the lobby spawn the first time an admin is there; /npc setup
 * puts all of them in front of you again, /npc add bot|kit|arena|preview places one where you stand and /npc remove deletes
 * the closest one.
 * <p>
 * An NPC is a vanilla mannequin that cannot be pushed or hurt and carries the scoreboard tag pvpduels_npc_&lt;kind&gt;,
 * so it is saved with the world like any entity and nothing else has to be stored.
 */
public final class Npcs {
	static final String TAG_PREFIX = "pvpduels_npc_";

	enum Kind {
		BOT("bot", "Practice Bot", "Click to practice", "green", "kai"),
		KIT("kit", "Kit Workshop", "Click to create a kit", "aqua", "noor"),
		ARENA("arena", "Arena Builder", "Click to create an arena", "gold", "zuri"),
		PREVIEW("preview", "Explosion Dummy", "Click to try your explosions", "red", "sunny");

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

	/** How far in front of the lobby spawn the NPCs stand and how far apart they are. */
	static final double FORWARD = 4.5;
	static final double SPACING = 3.0;
	/** An NPC of a kind within this many blocks of the spawn counts as already placed. */
	static final double NEAR = 40.0;
	/** Admins this close to the lobby spawn get the NPCs placed for them (the chunks around them are loaded). */
	private static final double ADMIN_REACH = 24.0;
	/** The self-test turns the automatic placement off so it does not add NPCs in the middle of other tests. */
	static boolean autoPlace = true;
	/** Who counts as an admin for the automatic placement (the self-test has no real operators). */
	static java.util.function.Predicate<ServerPlayer> isAdmin = BuilderRank::isAdmin;

	private Npcs() {}

	/** Which NPC this entity is, or null. */
	static Kind kindOf(Entity entity) {
		if (entity == null || entity.entityTags().isEmpty()) return null;
		for (Kind k : Kind.values()) if (entity.entityTags().contains(k.tag())) return k;
		return null;
	}

	/** A click on an NPC. */
	static void interact(ServerPlayer p, Kind kind, Entity npc) {
		if (DuelManager.isBusy(p.getUUID())) {
			p.sendSystemMessage(Msg.err("You are busy: use /leave first."));
			return;
		}
		switch (kind) {
			case BOT -> DuelMenus.openBot(p, null);
			case KIT -> LobbyKit.openKitMenu(p);
			case ARENA -> LobbyKit.openArenaInfo(p);
			case PREVIEW -> EffectsMenus.openTryOut(p, npc.position());
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
		// the summon command puts the mannequin in the world of its source: the world of the lobby, not the main one
		level.getServer().getCommands().performPrefixedCommand(source.withLevel(level).withSuppressedOutput(), summonCommand(kind, x, y, z, yaw));
		return nearest(level, x, y, z, 1.5);
	}

	/** True when an NPC of this kind already stands within {@link #NEAR} blocks of the place. */
	static boolean hasKindNear(ServerLevel level, double x, double y, double z, Kind kind) {
		for (Entity e : around(level, x, y, z, NEAR)) {
			if (kindOf(e) == kind) return true;
		}
		return false;
	}

	/** What {@link #setup} did: how many NPCs it placed and how many could not be created. */
	record SetupResult(int placed, int failed) {}

	/**
	 * Puts the four NPCs in a row in front of a place (the lobby spawn, or where an admin stands), facing it. The ones that already
	 * stand near the place are left alone, so it can be run again safely.
	 */
	static SetupResult setup(ServerLevel level, CommandSourceStack source, double x, double y, double z, float yaw) {
		double rad = Math.toRadians(yaw);
		double fx = -Math.sin(rad);
		double fz = Math.cos(rad);
		double rx = -Math.cos(rad); // the right hand of whoever looks the way yaw says
		double rz = -Math.sin(rad);
		Kind[] kinds = Kind.values();
		int placed = 0;
		int failed = 0;
		for (int i = 0; i < kinds.length; i++) {
			if (hasKindNear(level, x, y, z, kinds[i])) continue;
			double lateral = (i - (kinds.length - 1) / 2.0) * SPACING;
			Entity npc = placeAt(level, source, kinds[i], x + fx * FORWARD + rx * lateral, y, z + fz * FORWARD + rz * lateral, yaw + 180f);
			if (npc == null) {
				failed++;
				PvpDuelsMod.LOGGER.warn("Could not place the {} NPC near {} {} {}", kinds[i].id, x, y, z);
			} else {
				placed++;
			}
		}
		return new SetupResult(placed, failed);
	}

	/**
	 * Every two seconds: when the lobby has no NPCs yet and an admin is standing near its spawn, they are placed in front of it (once;
	 * after that only /npc setup does it, so an NPC an admin removed on purpose does not come back).
	 */
	private static void autoTick(MinecraftServer server) {
		if (!autoPlace || server.getTickCount() % 40 != 0 || !Lobby.isSet() || Lobby.npcsPlaced()) return;
		Lobby.Place spot = Lobby.spotIn(server);
		if (spot == null) return;
		ServerPlayer admin = null;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.level() != spot.level() || !isAdmin.test(p) || BotDuel.isBot(p.getUUID())) continue;
			double dx = p.getX() - spot.x();
			double dz = p.getZ() - spot.z();
			if (dx * dx + dz * dz <= ADMIN_REACH * ADMIN_REACH) {
				admin = p;
				break;
			}
		}
		if (admin == null) return;
		SetupResult result = setup(spot.level(), server.createCommandSourceStack(), spot.x(), spot.y(), spot.z(), spot.yaw());
		if (result.failed() > 0) return; // try again in two seconds
		Lobby.markNpcsPlaced();
		if (result.placed() > 0) {
			admin.sendSystemMessage(Msg.ok("The lobby NPCs are in front of the spawn: Practice Bot, Kit Workshop, Arena Builder and Explosion Dummy. "
				+ "/npc remove deletes the closest one, /npc setup puts the missing ones back."));
		}
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
		ServerTickEvents.END_SERVER_TICK.register(Npcs::autoTick);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			Kind kind = kindOf(entity);
			if (kind == null) return InteractionResult.PASS;
			interact(sp, kind, entity);
			return InteractionResult.SUCCESS;
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			Kind kind = kindOf(entity);
			if (kind == null) return InteractionResult.PASS;
			// Hitting an NPC works like clicking it. Admins in creative can still remove it with /npc remove.
			interact(sp, kind, entity);
			return InteractionResult.FAIL;
		});
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("npc")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("add")
				.then(Commands.argument("kind", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("bot", "kit", "arena", "preview"), builder))
					.executes(ctx -> {
						ServerPlayer p = ctx.getSource().getPlayerOrException();
						Kind kind = Kind.byId(StringArgumentType.getString(ctx, "kind"));
						if (kind == null) {
							ctx.getSource().sendFailure(Msg.err("The NPC can be: bot, kit, arena or preview."));
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
			.then(Commands.literal("setup").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				SetupResult result = setup((ServerLevel) p.level(), p.createCommandSourceStack(), p.getX(), p.getY(), p.getZ(), p.getYRot());
				if (result.failed() > 0) {
					ctx.getSource().sendFailure(Msg.err(result.failed() + " NPC(s) could not be created. Look at the game log."));
					return 0;
				}
				if (result.placed() == 0) {
					ctx.getSource().sendSuccess(() -> Msg.info("All the NPCs are already within " + (int) NEAR + " blocks of you. /npc remove deletes the closest one."), false);
				} else {
					ctx.getSource().sendSuccess(() -> Msg.ok("Placed " + result.placed() + " NPC(s) in front of you, facing you."), false);
				}
				return result.placed();
			}))
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
					ctx.getSource().sendSuccess(() -> Msg.info("There are no NPCs. /npc setup places all of them in front of you, /npc add bot|kit|arena|preview places one where you stand."), false);
				} else {
					ctx.getSource().sendSuccess(() -> Msg.title("NPCs (" + lines.size() + ")"), false);
					for (String line : lines) ctx.getSource().sendSuccess(() -> Msg.info("  " + line), false);
				}
				return lines.size();
			})));
	}
}
