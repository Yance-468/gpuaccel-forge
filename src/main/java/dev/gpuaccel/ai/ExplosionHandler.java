package dev.gpuaccel.ai;

import dev.gpuaccel.GpuAcceleratorMod;
import dev.gpuaccel.gpu.GpuComputeEngine;
import net.minecraft.core.BlockPos;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * GPU explosion calculation: recomputes the blast-damage field on the GPU and replaces the
 * affected-block list. Demonstrates GPU ray-cast / blast computation during explosions.
 */
@Mod.EventBusSubscriber(modid = GpuAcceleratorMod.MODID)
public final class ExplosionHandler {

    private ExplosionHandler() {}

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        GpuComputeEngine engine = GpuAcceleratorMod.engine();
        if (engine == null) return;
        List<BlockPos> affected = event.getAffectedBlocks();
        if (affected.size() < 4) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : affected) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
        int r = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ) / 2 + 1;
        if (r > 16) r = 16;

        float[] damage;
        try {
            damage = engine.explosion(r, r * 2.0f);
        } catch (Throwable t) {
            return;
        }

        int n = 2 * r + 1;
        affected.clear();
        for (int gz = 0; gz < n; gz++) {
            for (int gy = 0; gy < n; gy++) {
                for (int gx = 0; gx < n; gx++) {
                    if (damage[gz * n * n + gy * n + gx] > 0.5f) {
                        affected.add(new BlockPos(cx + gx - r, cy + gy - r, cz + gz - r));
                    }
                }
            }
        }
    }
}
