package dev.gpuaccel;

import dev.gpuaccel.command.GpuCommand;
import dev.gpuaccel.gpu.GpuComputeEngine;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * GPU Accelerator: offloads suitable server-side computations (terrain heightmap generation
 * and benchmarks) to the GPU via OpenCL (LWJGL). Commands:
 * <ul>
 *     <li>/gpuaccel status - list detected OpenCL devices</li>
 *     <li>/gpuaccel benchmark [size] - CPU vs GPU heightmap timing</li>
 *     <li>/gpuaccel heightmap &lt;w&gt; &lt;h&gt; [seed] - generate a heightmap on the GPU</li>
 * </ul>
 */
@Mod("gpuaccel")
public class GpuAcceleratorMod {
    public static final String MODID = "gpuaccel";
    public static final Logger LOGGER = LogManager.getLogger("GPU Accelerator");

    private static GpuComputeEngine engine;

    public GpuAcceleratorMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static GpuComputeEngine engine() {
        return engine;
    }

    /** Lazily creates the GPU engine (called from commands, on the server thread). */
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
    public void onRegisterCommands(RegisterCommandsEvent event) {
        GpuCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }
}
