package dev.gpuaccel.ai;

import dev.gpuaccel.GpuAcceleratorMod;
import dev.gpuaccel.gpu.GpuComputeEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = GpuAcceleratorMod.MODID)
public final class MobFlowHandler {
    private static final double ATTRACTION_RADIUS = 32.0;
    private static final float MOVE_SPEED = 0.5f;

    private static final Map<BlockPos, FlowField> FIELDS = new HashMap<>();
    private static int refreshCounter;

    private MobFlowHandler() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        GpuComputeEngine engine = GpuAcceleratorMod.engine();
        if (engine == null) return;

        if (++refreshCounter >= 40) {
            refreshCounter = 0;
            FIELDS.clear();
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (!(entity instanceof Mob mob)) continue;
                    LivingEntity target = mob.getTarget();
                    if (target == null) continue;
                    BlockPos tp = target.blockPosition();
                    if (FIELDS.containsKey(tp)) continue;
                    FlowField field = FlowField.compute(level, tp);
                    if (field != null) FIELDS.put(tp, field);
                }
            }
        }

        if (FIELDS.isEmpty()) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (FlowField field : FIELDS.values()) {
                List<Mob> mobs = level.getEntitiesOfClass(Mob.class, field.regionAabb());
                int n = mobs.size();
                if (n == 0) continue;

                float[] positions = new float[n * 3];
                float[] flow = new float[n * 3];
                for (int i = 0; i < n; i++) {
                    Mob mob = mobs.get(i);
                    LivingEntity target = mob.getTarget();
                    Vec3 dir = Vec3.ZERO;
                    if (target != null && target.blockPosition().equals(field.targetPos())) {
                        dir = field.directionAt(mob.blockPosition());
                        if (dir == null) dir = Vec3.ZERO;
                        double dx = mob.getX() - field.targetX();
                        double dz = mob.getZ() - field.targetZ();
                        if (dx * dx + dz * dz > ATTRACTION_RADIUS * ATTRACTION_RADIUS) dir = Vec3.ZERO;
                    }
                    positions[i * 3] = (float) mob.getX();
                    positions[i * 3 + 1] = (float) mob.getY();
                    positions[i * 3 + 2] = (float) mob.getZ();
                    flow[i * 3] = (float) dir.x;
                    flow[i * 3 + 1] = (float) dir.y;
                    flow[i * 3 + 2] = (float) dir.z;
                }

                float[] steering;
                try {
                    steering = engine.boids(positions, flow, n);
                } catch (Throwable t) {
                    steering = null;
                }

                for (int i = 0; i < n; i++) {
                    Mob mob = mobs.get(i);
                    float sx = steering != null ? steering[i * 3] : flow[i * 3];
                    float sy = steering != null ? steering[i * 3 + 1] : flow[i * 3 + 1];
                    float sz = steering != null ? steering[i * 3 + 2] : flow[i * 3 + 2];
                    double len = Math.sqrt(sx * sx + sz * sz);
                    if (len < 0.001) continue;
                    double nx = sx / len, nz = sz / len;
                    double tx = mob.getX() + nx * FlowField.CELL_SIZE;
                    double tz = mob.getZ() + nz * FlowField.CELL_SIZE;
                    mob.getMoveControl().setWantedPosition(tx, mob.getY(), tz, MOVE_SPEED);
                    if (sy > 0.1) mob.setJumping(true);
                    mob.getNavigation().stop();
                }
            }
        }
    }
}
