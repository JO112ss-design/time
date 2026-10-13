package com.timerewind.mixin;

import com.timerewind.core.RewindManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * すべてのブロック変更（採掘・設置・爆発・火・水流・ピストン・エンダーマン等）は
 * Level#setBlock を通るので、ここで「変更前の状態」を記録する。
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD")
    )
    private void timerewind$beforeSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                           CallbackInfoReturnable<Boolean> cir) {
        Object self = this;
        if (self instanceof ServerLevel serverLevel) {
            RewindManager.get().beforeSetBlock(serverLevel, pos, state);
        }
    }
}
