package net.jahus.ilookat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * /ilookat — broadcast what the player is looking at.
 * See-through: water + glass. Stop on: lava, solid blocks, entities.
 */
public final class LookAtCommand {
	private static final Map<UUID, Long> LAST_USE = new ConcurrentHashMap<>();
	private static final long COOLDOWN_MS = 15_000L;
	private static final double REACH = 6.0;
	private static final int MAX_PASSTHROUGH = 16;

	private LookAtCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("ilookat")
				.executes(LookAtCommand::execute)
		);
	}

	private static int execute(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		if (source.getEntity() == null) {
			source.sendFailure(Component.literal("This command cannot be run from the server/console."));
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("You must be a player to run this command."));
			return 0;
		}

		UUID uuid = player.getUUID();
		long now = System.currentTimeMillis();
		Long last = LAST_USE.get(uuid);
		if (last != null && now - last < COOLDOWN_MS) {
			long secsLeft = (COOLDOWN_MS - (now - last) + 999) / 1000;
			source.sendSuccess(
				() -> Component.literal("Please wait " + secsLeft + " more second(s)."),
				false);
			return 0;
		}

		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition(1.0f);
		Vec3 look = player.getLookAngle();
		Vec3 end = eye.add(look.scale(REACH));

		Component targetName = resolveLookTarget(level, player, eye, end, look);
		if (targetName == null) {
			player.sendSystemMessage(Component.literal("You are not looking at anything."));
			return 0;
		}

		LAST_USE.put(uuid, now);

		MutableComponent message = Component.literal("")
			.append(Component.literal(player.getName().getString()).withStyle(ChatFormatting.GOLD))
			.append(Component.literal(" is looking at "))
			.append(targetName);

		source.getServer().getPlayerList().broadcastSystemMessage(message, false);
		return 1;
	}

	private static Component resolveLookTarget(ServerLevel level, ServerPlayer player, Vec3 eye, Vec3 end, Vec3 look) {
		Vec3 from = eye;

		for (int step = 0; step < MAX_PASSTHROUGH; step++) {
			EntityHitResult entityHit = hitEntity(level, player, from, end);
			BlockHitResult blockHit = level.clip(new ClipContext(
				from, end,
				ClipContext.Block.OUTLINE,
				ClipContext.Fluid.ANY,
				player
			));

			double entityDist = entityHit != null ? entityHit.getLocation().distanceToSqr(from) : Double.MAX_VALUE;
			double blockDist = blockHit.getType() != HitResult.Type.MISS
				? blockHit.getLocation().distanceToSqr(from)
				: Double.MAX_VALUE;

			if (entityHit != null && entityDist <= blockDist) {
				return describeEntity(entityHit.getEntity());
			}

			if (blockHit.getType() == HitResult.Type.MISS) {
				return null;
			}

			BlockPos pos = blockHit.getBlockPos();
			BlockState state = level.getBlockState(pos);

			if (isLava(state)) {
				return state.getBlock().getName();
			}

			if (isSeeThrough(state)) {
				from = blockHit.getLocation().add(look.scale(0.05));
				if (from.distanceToSqr(eye) > REACH * REACH) {
					return null;
				}
				continue;
			}

			return state.getBlock().getName();
		}
		return null;
	}

	private static EntityHitResult hitEntity(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to) {
		double reachSqr = from.distanceToSqr(to);
		AABB box = player.getBoundingBox().expandTowards(to.subtract(from)).inflate(1.0);
		Predicate<Entity> filter = e -> e != null && e.isPickable() && e != player;
		// 26.3: getEntityHitResult(Entity, Vec3, Vec3, AABB, Predicate, double)
		return ProjectileUtil.getEntityHitResult(player, from, to, box, filter, reachSqr);
	}

	private static boolean isLava(BlockState state) {
		return state.is(Blocks.LAVA) || state.getFluidState().is(FluidTags.LAVA);
	}

	private static boolean isSeeThrough(BlockState state) {
		if (state.is(Blocks.WATER) || state.getFluidState().is(FluidTags.WATER) || state.is(Blocks.BUBBLE_COLUMN)) {
			return true;
		}
		if (state.is(Blocks.GLASS)
				|| state.is(Blocks.GLASS_PANE)
				|| state.is(Blocks.TINTED_GLASS)
				|| state.is(Blocks.ICE)
				|| state.is(Blocks.FROSTED_ICE)) {
			return true;
		}
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return path.endsWith("_glass")
				|| path.endsWith("_glass_pane")
				|| path.endsWith("_stained_glass")
				|| path.endsWith("_stained_glass_pane");
	}

	private static Component describeEntity(Entity entity) {
		if (entity instanceof ItemFrame frame) {
			ItemStack framed = frame.getItem();
			if (!framed.isEmpty()) {
				return framed.getDisplayName();
			}
		}
		return entity.getDisplayName();
	}
}
