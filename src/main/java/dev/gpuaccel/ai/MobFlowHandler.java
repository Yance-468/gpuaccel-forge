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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hostile mobs follow a GPU-computed 3D flow field toward the nearest player. Each online
 * player gets their own flow field, refreshed every couple of seconds.
 */
@Mod.EventBusSubscriber(modid = GpuAcceleratorMod.MODID)
public final class MobFlowHandler {
    private static final Map<UUID, FlowField> FIELDS = new HashMap<>();
    private static int refreshCounter;

    private MobFlowHandler() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.getPlayerCount() == 0) return;

        if (++refreshCounter >= 40) {
            refreshCounter = 0;
            refreshFields(server);
        }
        if (FIELDS.isEmpty()) return;

        for (ServerLevel level : server.getAllLevels()) {
            for (FlowField field : FIELDS.values()) {
                for (Monster mob : level.getEntitiesOfClass(Monster.class, field.regionAabb())) {
                    Vec3 dir = field.directionAt(mob.blockPosition());
                    if (dir == null || dir == Vec3.ZERO) continue;
                    double tx = mob.getX() + dir.x * FlowField.CELL_SIZE;
                    double tz = mob.getZ() + dir.z * FlowField.CELL_SIZE;
                    mob.getMoveControl().setWantedPosition(tx, mob.getY(), tz, 1.3);
                    if (dir.y > 0) mob.setJumping(true);
                    mob.getNavigation().stop();
                }
            }
        }
    }

    private static void refreshFields(MinecraftServer server) {
        FIELDS.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            FlowField field = FlowField.compute(player.serverLevel(), player.blockPosition());
            if (field != null) FIELDS.put(player.getUUID(), field);
        }
    }
}
