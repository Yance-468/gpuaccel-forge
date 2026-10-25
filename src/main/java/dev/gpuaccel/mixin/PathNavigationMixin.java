package dev.gpuaccel.mixin;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AI-Improvements-style pathfinding throttle: only recompute the navigation path every other
 * tick instead of every tick, halving the A* search cost for mobs chasing moving targets.
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {

    @Unique
    private int gpuaccel$tickCounter = 0;

    @Inject(method = "recomputePath", at = @At("HEAD"), cancellable = true)
    private void gpuaccel$throttleRecompute(CallbackInfo ci) {
        if ((gpuaccel$tickCounter++ & 1) != 0) {
            ci.cancel();
        }
    }
}
