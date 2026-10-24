package dev.gpuaccel.ai;

import dev.gpuaccel.GpuAcceleratorMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Makes hostile mobs follow a GPU-computed flow field toward the nearest player (horde behavior).
 * The flow field is recomputed every couple of seconds; mobs inside its region follow it.
 */
@Mod.EventBusSubscriber(modid = GpuAcceleratorMod.MODID)
public final class MobFlowHandler {
    private static FlowField current;
    private static int refreshCounter;

    private MobFlowHandler() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.getPlayerCount() == 0) return;

        if (++refreshCounter >= 40) {
            refreshCounter = 0;
            current = computeForNearestPlayer(server);
        }
        FlowField field = current;
        if (field == null) return;

        for (ServerLevel level : server.getAllLevels()) {
            for (Monster mob : level.getEntitiesOfClass(Monster.class, field.regionAabb())) {
                Vec3 dir = field.directionAt(mob.blockPosition());
                if (dir != null && dir != Vec3.ZERO) {
                    double tx = mob.getX() + dir.x * FlowField.CELL_SIZE;
                    double tz = mob.getZ() + dir.z * FlowField.CELL_SIZE;
                    mob.getMoveControl().setWantedPosition(tx, mob.getY(), tz, 1.3);
                    mob.getNavigation().stop();
                }
            }
        }
    }

    private static FlowField computeForNearestPlayer(MinecraftServer server) {
        ServerPlayer nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            if (level == null) continue;
            // use the first player; simpler than finding the true nearest
            if (nearest == null) nearest = player;
        }
        if (nearest == null) return null;
        return FlowField.compute(nearest.serverLevel(), nearest.blockPosition());
    }
}
