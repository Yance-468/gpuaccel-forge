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
 * A {@link ChunkGenerator} whose terrain surface height is generated on the GPU.
 * Every (x, z) column is independent, so the whole region is computed in one OpenCL
 * kernel invocation and cached per region.
 *
 * <p>Terrain model (simple, deterministic): bedrock at the bottom, stone, then a few
 * dirt blocks, then grass/sand at the surface, water below sea level, air above.</p>
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
    private static final int SURFACE_DEPTH = 3;
    /** Upper bound on cached 256x256 regions (~256 KB each) to keep memory bounded during large pre-generation (e.g. Chunky). */
    private static final int MAX_CACHED_REGIONS = 512;

    private final float scale;
    private final float baseHeight;
    private final float amplitude;
    private final int seed;

    /** Region-keyed cache of GPU-generated heightmaps (region of REGION_SIZE x REGION_SIZE columns). */
    private final ConcurrentHashMap<Long, float[]> heightCache = new ConcurrentHashMap<>();

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

    // ---- height lookup with region caching ----

    private float getHeightAt(int x, int z) {
        int rx = Math.floorDiv(x, REGION_SIZE);
        int rz = Math.floorDiv(z, REGION_SIZE);
        long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
        float[] region = heightCache.get(key);
        if (region == null) {
            float[] computed = generateRegion(rx, rz);
            float[] existing = heightCache.putIfAbsent(key, computed);
            region = existing != null ? existing : computed;
            // Keep memory bounded during large pre-generation (e.g. Chunky).
            if (heightCache.size() > MAX_CACHED_REGIONS) {
                heightCache.clear();
            }
        }
        int lx = x - rx * REGION_SIZE;
        int lz = z - rz * REGION_SIZE;
        return region[lz * REGION_SIZE + lx];
    }

    private float[] generateRegion(int rx, int rz) {
        int wx = rx * REGION_SIZE;
        int wz = rz * REGION_SIZE;
        GpuComputeEngine engine = GpuAcceleratorMod.ensureEngine();
        if (engine != null) {
            return engine.heightmap(REGION_SIZE, REGION_SIZE, scale, baseHeight, amplitude, seed, wx, wz);
        }
        return CpuNoise.heightmap(REGION_SIZE, REGION_SIZE, scale, baseHeight, amplitude, seed, wx, wz);
    }

    private BlockState blockFor(int y, int surface) {
        if (y <= MIN_Y) return Blocks.BEDROCK.defaultBlockState();
        if (y < surface - SURFACE_DEPTH) return Blocks.STONE.defaultBlockState();
        if (y < surface) return Blocks.DIRT.defaultBlockState();
        if (y == surface) return surface < SEA_LEVEL ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
        if (y <= SEA_LEVEL) return Blocks.WATER.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
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

        for (int x = 0; x < 16; x++) {
            int wx = startX + x;
            for (int z = 0; z < 16; z++) {
                int wz = startZ + z;
                int surface = (int) Math.floor(getHeightAt(wx, wz));
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(wx, minY, wz);
                for (int y = minY; y < maxY; y++) {
                    cursor.setY(y);
                    chunk.setBlockState(cursor, blockFor(y, surface), false);
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        return (int) Math.floor(getHeightAt(x, z));
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        int minY = level.getMinBuildHeight();
        int height = level.getHeight();
        int surface = (int) Math.floor(getHeightAt(x, z));
        BlockState[] column = new BlockState[height];
        for (int i = 0; i < height; i++) {
            column[i] = blockFor(minY + i, surface);
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
        // no caves in the GPU terrain (kept simple)
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
        // surface is already placed in fillFromNoise
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // no mobs spawned during generation
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        // optional debug info omitted
    }
}
