package dev.gpuaccel.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Radium/Lithium-style entity AI throttle: run server AI every other tick instead of every
 * tick, halving the per-mob AI cost (goal selection + navigation) for large mob counts.
 */
@Mixin(Mob.class)
public abstract class MobMixin {

    @Unique
    private int gpuaccel$aiTickCounter = 0;

    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void gpuaccel$throttleAiStep(CallbackInfo ci) {
        if ((gpuaccel$aiTickCounter++ & 1) != 0) {
            ci.cancel();
        }
    }
}
