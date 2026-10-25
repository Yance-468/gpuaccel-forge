package dev.gpuaccel.ai;

import dev.gpuaccel.GpuAcceleratorMod;
import dev.gpuaccel.gpu.GpuComputeEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * GPU fluid simulation demo: periodically runs a 2D fluid-spread automaton around the spawn
 * point and places flowing water in the newly reached air cells, showing the GPU fluid sim.
 */
@Mod.EventBusSubscriber(modid = GpuAcceleratorMod.MODID)
public final class FluidHandler {
    private static int counter;

    private FluidHandler() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        GpuComputeEngine engine = GpuAcceleratorMod.engine();
        if (engine == null) return;
        if (++counter % 20 != 0) return;

        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int w = 32, h = 32;
        int y = spawn.getY();
        byte[] grid = new byte[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                BlockPos p = new BlockPos(spawn.getX() - w / 2 + x, y, spawn.getZ() - h / 2 + z);
                grid[z * w + x] = level.getFluidState(p).isSource() ? (byte) 1 : (byte) 0;
            }
        }

        byte[] cur;
        try {
            cur = grid;
            for (int step = 0; step < 4; step++) {
                cur = engine.fluid(cur, w, h);
            }
        } catch (Throwable t) {
            return;
        }

        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                if (cur[z * w + x] == 1 && grid[z * w + x] == 0) {
                    BlockPos p = new BlockPos(spawn.getX() - w / 2 + x, y, spawn.getZ() - h / 2 + z);
                    if (level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.WATER.defaultBlockState(), 3);
                    }
                }
            }
        }
    }
}
