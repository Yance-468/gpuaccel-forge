package dev.gpuaccel.ai;

import dev.gpuaccel.GpuAcceleratorMod;
import dev.gpuaccel.gpu.GpuComputeEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class FlowField {
    public static final int NX = 32;
    public static final int NZ = 32;
    public static final int NY = 8;
    public static final int CELL_SIZE = 3;
    public static final int Y_STEP = 4;
    public static final int WORLD_X = NX * CELL_SIZE;
    public static final int WORLD_Z = NZ * CELL_SIZE;

    private final int originX;
    private final int originZ;
    private final int minY;
    private final int targetX;
    private final int targetY;
    private final int targetZ;
    private final byte[] dirs;

    private FlowField(int originX, int originZ, int minY, int targetX, int targetY, int targetZ, byte[] dirs) {
        this.originX = originX;
        this.originZ = originZ;
        this.minY = minY;
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.dirs = dirs;
    }

    public static FlowField compute(Level level, BlockPos target) {
        GpuComputeEngine engine = GpuAcceleratorMod.ensureEngine();
        if (engine == null) return null;
        int originX = target.getX() - WORLD_X / 2;
        int originZ = target.getZ() - WORLD_Z / 2;
        int minY = target.getY() - (NY * Y_STEP) / 2;
        int targetX = NX / 2;
        int targetZ = NZ / 2;
        int targetY = Math.floorDiv(target.getY() - minY, Y_STEP);
        if (targetY < 0 || targetY >= NY) targetY = NY / 2;

        byte[] walkable = new byte[NX * NY * NZ];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < NY; y++) {
            int wy = minY + y * Y_STEP;
            for (int z = 0; z < NZ; z++) {
                int wz = originZ + z * CELL_SIZE + 1;
                for (int x = 0; x < NX; x++) {
                    int wx = originX + x * CELL_SIZE + 1;
                    pos.set(wx, wy, wz);
                    BlockState state = level.getBlockState(pos);
                    walkable[y * (NX * NZ) + z * NX + x] = state.isAir() ? (byte) 1 : (byte) 0;
                }
            }
        }
        walkable[targetY * (NX * NZ) + targetZ * NX + targetX] = 1;

        byte[] dirs = engine.flowField(walkable, NX, NY, NZ, targetX, targetY, targetZ);
        return new FlowField(originX, originZ, minY, target.getX(), target.getY(), target.getZ(), dirs);
    }

    public Vec3 directionAt(BlockPos pos) {
        int cx = Math.floorDiv(pos.getX() - originX, CELL_SIZE);
        int cz = Math.floorDiv(pos.getZ() - originZ, CELL_SIZE);
        int cy = Math.floorDiv(pos.getY() - minY, Y_STEP);
        if (cx < 0 || cx >= NX || cz < 0 || cz >= NZ || cy < 0 || cy >= NY) return null;
        int dir = dirs[cy * (NX * NZ) + cz * NX + cx] & 0xFF;
        switch (dir) {
            case 1: return new Vec3(1, 0, 0);
            case 2: return new Vec3(-1, 0, 0);
            case 3: return new Vec3(0, 0, 1);
            case 4: return new Vec3(0, 0, -1);
            case 5: return new Vec3(0.7071, 0, 0.7071);
            case 6: return new Vec3(-0.7071, 0, -0.7071);
            case 7: return new Vec3(0.7071, 0, -0.7071);
            case 8: return new Vec3(-0.7071, 0, 0.7071);
            case 9: return new Vec3(0, 1, 0);
            case 10: return new Vec3(0, -1, 0);
            default: return Vec3.ZERO;
        }
    }

    public int targetX() { return targetX; }
    public int targetY() { return targetY; }
    public int targetZ() { return targetZ; }
    public BlockPos targetPos() { return new BlockPos(targetX, targetY, targetZ); }

    public AABB regionAabb() {
        return new AABB(originX, minY, originZ, originX + WORLD_X, minY + NY * Y_STEP, originZ + WORLD_Z);
    }
}
