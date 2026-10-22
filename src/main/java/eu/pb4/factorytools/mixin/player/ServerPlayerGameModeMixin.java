package eu.pb4.factorytools.mixin.player;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import eu.pb4.factorytools.api.block.CustomBreakingParticleBlock;
import eu.pb4.factorytools.api.block.SneakBypassingBlock;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {
    @Shadow @Final protected ServerPlayer player;

    @Shadow protected ServerLevel level;

    @Shadow private BlockPos destroyPos;

    @Shadow private Direction destroyDirection;

    @ModifyExpressionValue(
            method = "useItemOn",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSecondaryUseActive()Z")
    )
    private boolean dontCancelForSome(boolean original, @Local BlockState state, @Local ServerPlayer player, @Local ItemStack stack, @Local(argsOnly = true) InteractionHand hand, @Local(argsOnly = true) BlockHitResult result) {
        return !(state.getBlock() instanceof SneakBypassingBlock b && b.bypassSneaking(state, player, result, stack, hand)) && original;
    }

    // Next to vanilla's per tick destroy progress event, whose hit particles come from each client's own block state
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V"))
    private void sendHitParticles(CallbackInfo ci) {
        var pos = this.destroyPos;
        var state = this.level.getBlockState(pos);
        if (!(PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, state.getBlock()) instanceof CustomBreakingParticleBlock block)
                || !state.shouldSpawnTerrainParticles()) {
            return;
        }

        ParticleOptions particle = null;
        Vec3 position = null;
        Vec3 motion = null;
        var center = Vec3.atCenterOf(pos);
        for (var viewer : this.level.players()) {
            if (!viewer.position().closerThan(center, 32) || !block.shouldSendHitParticles(state, pos, viewer)) {
                continue;
            }
            if (particle == null) {
                particle = block.getBreakingParticle(state);
                position = this.factorytools$hitParticlePosition(state, pos, this.destroyDirection);
                motion = this.factorytools$hitParticleMotion();
            }
            this.level.sendParticles(viewer, particle, false, false, position.x, position.y, position.z, 0, motion.x, motion.y, motion.z, 1);
        }
    }

    // As the client places them: anywhere on the hit face of the block's shape, 0.1 out
    @Unique
    private Vec3 factorytools$hitParticlePosition(BlockState state, BlockPos pos, Direction direction) {
        var random = this.level.getRandom();
        var shape = state.getShape(this.level, pos);
        var box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
        double x = random.nextDouble() * (box.maxX - box.minX - 0.2) + 0.1 + box.minX;
        double y = random.nextDouble() * (box.maxY - box.minY - 0.2) + 0.1 + box.minY;
        double z = random.nextDouble() * (box.maxZ - box.minZ - 0.2) + 0.1 + box.minZ;
        switch (direction) {
            case DOWN -> y = box.minY - 0.1;
            case UP -> y = box.maxY + 0.1;
            case NORTH -> z = box.minZ - 0.1;
            case SOUTH -> z = box.maxZ + 0.1;
            case WEST -> x = box.minX - 0.1;
            case EAST -> x = box.maxX + 0.1;
        }
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    // Vanilla's hit particle motion (power 0.2); item particles keep it, block particles get rerandomized
    // by the client at full power
    @Unique
    private Vec3 factorytools$hitParticleMotion() {
        var random = this.level.getRandom();
        double x = random.nextFloat() * 2 - 1;
        double y = random.nextFloat() * 2 - 1;
        double z = random.nextFloat() * 2 - 1;
        double speed = (random.nextFloat() + random.nextFloat() + 1) * 0.15 * 0.4 * 0.2;
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length == 0) {
            return new Vec3(0, 0.1, 0);
        }
        return new Vec3(x / length * speed, y / length * speed + 0.1, z / length * speed);
    }
}
