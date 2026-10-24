package dev.gpuaccel.ai;

import dev.gpuaccel.GpuAcceleratorMod;
import dev.gpuaccel.gpu.GpuComputeEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A GPU-computed flow field around a target. Each cell stores a direction toward the target;
 * mobs follow it to reach the target while avoiding obstacles.
 */
public final class FlowField {
    public static final int GRID_N = 32;
    public static final int CELL_SIZE = 3;
    public static final int WORLD_SIZE = GRID_N * CELL_SIZE;

    private final int originX;
    private final int originZ;
    private final int y;
    private final byte[] dirs;

    private FlowField(int originX, int originZ, int y, byte[] dirs) {
        this.originX = originX;
        this.originZ = originZ;
        this.y = y;
        this.dirs = dirs;
    }

    /** Computes a flow field toward the target, or null if no GPU is available. */
    public static FlowField compute(Level level, BlockPos target) {
        GpuComputeEngine engine = GpuAcceleratorMod.ensureEngine();
        if (engine == null) return null;
        int originX = target.getX() - WORLD_SIZE / 2;
        int originZ = target.getZ() - WORLD_SIZE / 2;
        int y = target.getY();
        int targetCellX = GRID_N / 2;
        int targetCellZ = GRID_N / 2;

        byte[] walkable = new byte[GRID_N * GRID_N];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cz = 0; cz < GRID_N; cz++) {
            for (int cx = 0; cx < GRID_N; cx++) {
                pos.set(originX + cx * CELL_SIZE + 1, y, originZ + cz * CELL_SIZE + 1);
                BlockState state = level.getBlockState(pos);
                walkable[cz * GRID_N + cx] = state.isAir() ? (byte) 1 : (byte) 0;
            }
        }
        walkable[targetCellZ * GRID_N + targetCellX] = 1;

        byte[] dirs = engine.flowField(walkable, GRID_N, targetCellX, targetCellZ);
        return new FlowField(originX, originZ, y, dirs);
    }

    /** Flow direction (unit xz vector) for a world position, or null if outside/unknown. */
    public Vec3 directionAt(BlockPos pos) {
        int cx = Math.floorDiv(pos.getX() - originX, CELL_SIZE);
        int cz = Math.floorDiv(pos.getZ() - originZ, CELL_SIZE);
        if (cx < 0 || cx >= GRID_N || cz < 0 || cz >= GRID_N) return null;
        int dir = dirs[cz * GRID_N + cx] & 0xFF;
        switch (dir) {
            case 1: return new Vec3(1, 0, 0);
            case 2: return new Vec3(-1, 0, 0);
            case 3: return new Vec3(0, 0, 1);
            case 4: return new Vec3(0, 0, -1);
            default: return Vec3.ZERO;
        }
    }

    public AABB regionAabb() {
        return new AABB(originX, y - 12, originZ, originX + WORLD_SIZE, y + 12, originZ + WORLD_SIZE);
    }
}
