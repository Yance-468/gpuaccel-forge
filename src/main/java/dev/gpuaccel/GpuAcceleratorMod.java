package dev.gpuaccel;

import dev.gpuaccel.gpu.GpuComputeEngine;
import dev.gpuaccel.network.GpuNetwork;
import dev.gpuaccel.network.GpuStatusSnapshot;
import dev.gpuaccel.worldgen.GpuWorldTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
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
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

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
        GpuNetwork.register();
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

    /** Snapshot of the current server status (GPU + CPU + memory + world size), sent to the client GUI. */
    public static GpuStatusSnapshot status(MinecraftServer server) {
        GpuComputeEngine e = engine;
        GpuStatusSnapshot gpu = (e != null) ? e.snapshot() : GpuStatusSnapshot.unavailable(GpuComputeEngine.lastError());

        double systemCpu = 0.0;
        double processCpu = 0.0;
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double s = os.getSystemCpuLoad();
            double p = os.getProcessCpuLoad();
            if (s >= 0) systemCpu = s * 100.0;
            if (p >= 0) processCpu = p * 100.0;
        } catch (Throwable ignored) {
        }

        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();

        long worldSize = 0;
        if (server != null) {
            try {
                Path worldDir = server.getWorldPath(LevelResource.ROOT);
                try (Stream<Path> stream = Files.walk(worldDir)) {
                    worldSize = stream.filter(Files::isRegularFile).mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException ex) { return 0; }
                    }).sum();
                }
            } catch (Throwable ignored) {
            }
        }

        return gpu.withServerStats(systemCpu, processCpu, heapUsed, heapMax, worldSize);
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
