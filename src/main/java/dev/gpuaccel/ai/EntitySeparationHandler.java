package dev.gpuaccel.ai;

import dev.gpuaccel.GpuAcceleratorMod;
import dev.gpuaccel.gpu.GpuComputeEngine;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * GPU-accelerated entity collision detection: the GPU computes per-entity push-away vectors
 * (O(N^2) in parallel) for crowded entities, and the CPU applies the result as gentle velocity.
 */
@Mod.EventBusSubscriber(modid = GpuAcceleratorMod.MODID)
public final class EntitySeparationHandler {
    private static int counter;

    private EntitySeparationHandler() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        GpuComputeEngine engine = GpuAcceleratorMod.engine();
        if (engine == null) return;
        if (++counter % 5 != 0) return;

        for (ServerLevel level : server.getAllLevels()) {
            List<LivingEntity> entities = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof LivingEntity living) entities.add(living);
            }
            int n = entities.size();
            if (n < 2) continue;

            float[] positions = new float[n * 3];
            for (int i = 0; i < n; i++) {
                LivingEntity e = entities.get(i);
                positions[i * 3] = (float) e.getX();
                positions[i * 3 + 1] = (float) e.getY();
                positions[i * 3 + 2] = (float) e.getZ();
            }

            float[] sep;
            try {
                sep = engine.separation(positions, n);
            } catch (Throwable t) {
                continue;
            }

            for (int i = 0; i < n; i++) {
                LivingEntity e = entities.get(i);
                float sx = sep[i * 3];
                float sz = sep[i * 3 + 2];
                if (sx == 0.0f && sz == 0.0f) continue;
                Vec3 v = e.getDeltaMovement();
                e.setDeltaMovement(v.x + sx * 0.15, v.y, v.z + sz * 0.15);
            }
        }
    }
}
