package dev.gpuaccel.worldgen;

import com.mojang.serialization.Codec;
import dev.gpuaccel.GpuAcceleratorMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registers the GPU terrain chunk-generator codec. The world preset itself is data-driven:
 * see {@code data/gpuaccel/worldgen/world_preset/gpu_terrain.json}.
 */
public final class GpuWorldTypes {
    private GpuWorldTypes() {}

    private static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, GpuAcceleratorMod.MODID);

    public static final RegistryObject<Codec<GpuChunkGenerator>> GPU_TERRAIN_CODEC =
            CHUNK_GENERATORS.register("gpu_terrain", () -> GpuChunkGenerator.CODEC);

    public static void register(IEventBus bus) {
        CHUNK_GENERATORS.register(bus);
    }
}
