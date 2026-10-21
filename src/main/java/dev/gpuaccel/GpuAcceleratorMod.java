package dev.gpuaccel;

import dev.gpuaccel.gpu.GpuComputeEngine;
import dev.gpuaccel.worldgen.GpuWorldTypes;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * GPU Accelerator: offloads suitable server-side computations (world-generation heightmaps)
 * to the GPU via OpenCL (LWJGL). The GPU engine is initialized automatically when the
 * dedicated server starts.
 */
@Mod("gpuaccel")
public class GpuAcceleratorMod {
    public static final String MODID = "gpuaccel";
    public static final Logger LOGGER = LogManager.getLogger("GPU Accelerator");

    private static GpuComputeEngine engine;

    public GpuAcceleratorMod() {
        MinecraftForge.EVENT_BUS.register(this);
        GpuWorldTypes.register(FMLJavaModLoadingContext.get().getModEventBus());
    }

    public static GpuComputeEngine engine() {
        return engine;
    }

    /** Creates the GPU engine and compiles the heightmap kernel. */
    public static synchronized GpuComputeEngine ensureEngine() {
        if (engine == null) {
            String source = loadKernel();
            if (source == null) {
                LOGGER.error("Failed to load the heightmap.cl kernel resource.");
                return null;
            }
            engine = GpuComputeEngine.tryCreate(source);
            if (engine != null) {
                LOGGER.info("GPU compute engine ready: {}", engine.deviceInfo());
            } else {
                LOGGER.warn("GPU compute engine unavailable: {}", GpuComputeEngine.lastError());
            }
        }
        return engine;
    }

    private static String loadKernel() {
        try (InputStream in = GpuAcceleratorMod.class.getResourceAsStream("/kernels/heightmap.cl")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to read heightmap.cl", e);
            return null;
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ensureEngine();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }
}
