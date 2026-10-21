package eu.pb4.factorytools.api.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

public interface CustomBreakingParticleBlock {
    ParticleOptions getBreakingParticle(BlockState state);

    /**
     * Whether the player's client shows no hit particles of its own while this block is mined, like for an invisible
     * or empty client state. The server then sends that player {@link #getBreakingParticle} at the hit face each tick.
     */
    default boolean shouldSendHitParticles(BlockState state, BlockPos pos, ServerPlayer player) {
        return false;
    }
}
