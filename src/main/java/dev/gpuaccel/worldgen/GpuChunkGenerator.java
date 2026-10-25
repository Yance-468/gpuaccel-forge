package dev.gpuaccel.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.gpuaccel.GpuAcceleratorMod;
import dev.gpuaccel.compute.CpuNoise;
import dev.gpuaccel.gpu.GpuComputeEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * A {@link ChunkGenerator} whose terrain (surface + caves + overhangs) is a 3D density field
 * generated on the GPU. The density is sampled every {@link #Y_STEP} blocks vertically.
 */
public final class GpuChunkGenerator extends ChunkGenerator {

    public static final Codec<GpuChunkGenerator> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    Codec.FLOAT.fieldOf("scale").forGetter(g -> g.scale),
                    Codec.FLOAT.fieldOf("base_height").forGetter(g -> g.baseHeight),
                    Codec.FLOAT.fieldOf("amplitude").forGetter(g -> g.amplitude),
                    Codec.INT.fieldOf("seed").forGetter(g -> g.seed)
            ).apply(instance, GpuChunkGenerator::new));

    private static final int SEA_LEVEL = 63;
    private static final int MIN_Y = -64;
    private static final int WORLD_HEIGHT = 384;
    private static final int REGION_SIZE = 256;
    private static final int Y_STEP = 4;
    private static final int H_SAMPLES = WORLD_HEIGHT / Y_STEP;
    private static final int SURFACE_DEPTH = 3;
    private static final int MAX_CACHED_REGIONS = 8;
    private static final float CAVE_SCALE = 0.06f;
    private static final float CAVE_AMP = 35.0f;

    private static final byte B_AIR = 0;
    private static final byte B_STONE = 1;
    private static final byte B_WATER = 5;
    private static final byte B_BEDROCK = 6;
    private static final byte B_LAVA = 10;
    private static final byte B_COAL = 11;
    private static final byte B_IRON = 12;
    private static final byte B_GOLD = 13;
    private static final byte B_DIAMOND = 14;

    private final float scale;
    private final float baseHeight;
    private final float amplitude;
    private final int seed;

    private final ConcurrentHashMap<Long, byte[]> densityCache = new ConcurrentHashMap<>();

    public GpuChunkGenerator(BiomeSource biomeSource, float scale, float baseHeight, float amplitude, int seed) {
        super(biomeSource);
        this.scale = scale;
        this.baseHeight = baseHeight;
        this.amplitude = amplitude;
        this.seed = seed;
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ---- density lookup with region caching ----

    private byte[] regionAt(int x, int z) {
        int rx = Math.floorDiv(x, REGION_SIZE);
        int rz = Math.floorDiv(z, REGION_SIZE);
        long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
        byte[] region = densityCache.get(key);
        if (region == null) {
            byte[] computed = generateRegion(rx, rz);
            byte[] existing = densityCache.putIfAbsent(key, computed);
            region = existing != null ? existing : computed;
            if (densityCache.size() > MAX_CACHED_REGIONS) {
                densityCache.clear();
            }
        }
        return region;
    }

    private byte density(byte[] region, int wx, int wz, int wy) {
        int gx = Math.floorMod(wx, REGION_SIZE);
        int gz = Math.floorMod(wz, REGION_SIZE);
        int gy = (wy - MIN_Y) / Y_STEP;
        if (gy < 0) gy = 0;
        if (gy >= H_SAMPLES) gy = H_SAMPLES - 1;
        return region[gy * (REGION_SIZE * REGION_SIZE) + gz * REGION_SIZE + gx];
    }

    private byte[] generateRegion(int rx, int rz) {
        int wx = rx * REGION_SIZE;
        int wz = rz * REGION_SIZE;
        GpuComputeEngine engine = GpuAcceleratorMod.ensureEngine();
        if (engine != null) {
            return engine.density3d(REGION_SIZE, REGION_SIZE, H_SAMPLES, scale, baseHeight, amplitude, CAVE_SCALE, CAVE_AMP, seed, wx, wz, MIN_Y, SEA_LEVEL);
        }
        return CpuNoise.density3d(REGION_SIZE, REGION_SIZE, H_SAMPLES, scale, baseHeight, amplitude, CAVE_SCALE, CAVE_AMP, seed, wx, wz, MIN_Y, SEA_LEVEL);
    }

    private BlockState blockFor(int x, int z, int y, int surface, byte d) {
        if (d == B_BEDROCK || y <= MIN_Y) return Blocks.BEDROCK.defaultBlockState();
        if (d == B_COAL) return Blocks.COAL_ORE.defaultBlockState();
        if (d == B_IRON) return Blocks.IRON_ORE.defaultBlockState();
        if (d == B_GOLD) return Blocks.GOLD_ORE.defaultBlockState();
        if (d == B_DIAMOND) return Blocks.DIAMOND_ORE.defaultBlockState();
        if (d == B_STONE) {
            if (y == surface) return biomeSurface(x, z, surface);
            if (y >= surface - SURFACE_DEPTH) return Blocks.DIRT.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }
        if (d == B_LAVA) return Blocks.LAVA.defaultBlockState();
        if (d == B_WATER) return Blocks.WATER.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    /** Simple climate-based biome: grass plains / desert sand / snow / mountain stone. */
    private BlockState biomeSurface(int x, int z, int surfaceY) {
        float temp = CpuNoise.fbm(x * 0.002f, z * 0.002f, seed);
        if (surfaceY > 95) return Blocks.STONE.defaultBlockState();
        if (surfaceY > 78) return temp > 0.5f ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState();
        if (temp > 0.65f) return Blocks.SAND.defaultBlockState();
        if (temp < 0.35f) return Blocks.SNOW_BLOCK.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    /** Places a small oak tree (trunk + leaves) with its base at (x, y, z). */
    private void placeTree(ChunkAccess chunk, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 4; i++) {
            p.set(x, y + i, z);
            chunk.setBlockState(p, Blocks.OAK_LOG.defaultBlockState(), false);
        }
        for (int dy = 3; dy <= 4; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && dy == 3) continue;
                    p.set(x + dx, y + dy, z + dz);
                    chunk.setBlockState(p, Blocks.OAK_LEAVES.defaultBlockState(), false);
                }
            }
        }
    }

    // ---- abstract ChunkGenerator methods ----

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int startX = pos.getMinBlockX();
        int startZ = pos.getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = minY + chunk.getHeight();
        byte[] region = regionAt(startX, startZ);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(startX, minY, startZ);
        for (int x = 0; x < 16; x++) {
            int wx = startX + x;
            for (int z = 0; z < 16; z++) {
                int wz = startZ + z;
                int surface = MIN_Y - 1;
                for (int y = maxY - 1; y >= minY; y--) {
                    byte d = density(region, wx, wz, y);
                    if (d == B_STONE || d == B_BEDROCK) {
                        surface = y;
                        break;
                    }
                }
                for (int y = minY; y < maxY; y++) {
                    byte d = density(region, wx, wz, y);
                    cursor.set(wx, y, wz);
                    chunk.setBlockState(cursor, blockFor(wx, wz, y, surface, d), false);
                }
                // trees on grass plains (only when they fit within this chunk)
                if (x >= 1 && x <= 14 && z >= 1 && z <= 14 && surface > MIN_Y) {
                    byte sd = density(region, wx, surface, wz);
                    if (sd == B_STONE && biomeSurface(wx, wz, surface).is(Blocks.GRASS_BLOCK)
                            && CpuNoise.fbm(wx * 0.05f, wz * 0.05f, seed + 7777) > 0.90f) {
                        placeTree(chunk, wx, surface + 1, wz);
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        byte[] region = regionAt(x, z);
        int minY = level.getMinBuildHeight();
        int maxY = minY + level.getHeight();
        for (int y = maxY - 1; y >= minY; y--) {
            byte d = density(region, x, z, y);
            if (d == B_STONE || d == B_BEDROCK) return y;
        }
        return minY;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        byte[] region = regionAt(x, z);
        int minY = level.getMinBuildHeight();
        int height = level.getHeight();
        int surface = MIN_Y - 1;
        for (int y = minY + height - 1; y >= minY; y--) {
            byte d = density(region, x, z, y);
            if (d == B_STONE || d == B_BEDROCK) {
                surface = y;
                break;
            }
        }
        BlockState[] column = new BlockState[height];
        for (int i = 0; i < height; i++) {
            int y = minY + i;
            column[i] = blockFor(x, z, y, surface, density(region, x, z, y));
        }
        return new NoiseColumn(minY, column);
    }

    @Override
    public int getSeaLevel() {
        return SEA_LEVEL;
    }

    @Override
    public int getMinY() {
        return MIN_Y;
    }

    @Override
    public int getGenDepth() {
        return WORLD_HEIGHT;
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step) {
        // caves are already carved by the 3D density field
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
        // surface is already placed in fillFromNoise
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
    }
}
