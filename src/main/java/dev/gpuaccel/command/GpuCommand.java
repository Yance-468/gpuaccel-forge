package dev.gpuaccel.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.gpuaccel.GpuAcceleratorMod;
import dev.gpuaccel.compute.CpuNoise;
import dev.gpuaccel.gpu.GpuComputeEngine;
import dev.gpuaccel.gpu.GpuDeviceInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * /gpuaccel status | benchmark [size] | heightmap &lt;width&gt; &lt;height&gt; [seed]
 */
public final class GpuCommand {
    private GpuCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gpuaccel")
                .then(Commands.literal("status").executes(GpuCommand::status))
                .then(Commands.literal("benchmark")
                        .then(Commands.argument("size", IntegerArgumentType.integer(256, 2048))
                                .executes(ctx -> benchmark(ctx, IntegerArgumentType.getInteger(ctx, "size"))))
                        .executes(ctx -> benchmark(ctx, 1024)))
                .then(Commands.literal("heightmap")
                        .then(Commands.argument("width", IntegerArgumentType.integer(16, 2048))
                                .then(Commands.argument("height", IntegerArgumentType.integer(16, 2048))
                                        .executes(ctx -> heightmap(ctx,
                                                IntegerArgumentType.getInteger(ctx, "width"),
                                                IntegerArgumentType.getInteger(ctx, "height"),
                                                (int) (System.currentTimeMillis() & 0xFFFF)))))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        List<GpuDeviceInfo> devices = GpuComputeEngine.enumerateDevices();
        if (devices.isEmpty()) {
            String reason = GpuComputeEngine.lastError();
            src.sendFailure(Component.literal("No OpenCL device found" + (reason == null ? "." : ": " + reason)));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("GPU Accelerator - OpenCL devices:").withStyle(ChatFormatting.GREEN), false);
        for (GpuDeviceInfo d : devices) {
            src.sendSuccess(() -> Component.literal("  - " + d).withStyle(ChatFormatting.GRAY), false);
        }
        GpuComputeEngine engine = GpuAcceleratorMod.engine();
        if (engine != null) {
            src.sendSuccess(() -> Component.literal("Engine active on: " + engine.deviceInfo()).withStyle(ChatFormatting.GREEN), false);
        } else {
            src.sendSuccess(() -> Component.literal("Engine not created yet - run /gpuaccel benchmark or /gpuaccel heightmap.").withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int benchmark(CommandContext<CommandSourceStack> ctx, int size) {
        CommandSourceStack src = ctx.getSource();
        GpuComputeEngine engine = GpuAcceleratorMod.ensureEngine();
        if (engine == null) {
            src.sendFailure(Component.literal("GPU unavailable: " + GpuComputeEngine.lastError()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Running GPU vs CPU heightmap benchmark at " + size + "x" + size + " ...").withStyle(ChatFormatting.AQUA), false);

        long t0 = System.nanoTime();
        float[] gpu = engine.heightmap(size, size, 0.02f, 60f, 40f, 12345);
        long t1 = System.nanoTime();

        long c0 = System.nanoTime();
        float[] cpu = CpuNoise.heightmap(size, size, 0.02f, 60f, 40f, 12345);
        long c1 = System.nanoTime();

        double gpuMs = (t1 - t0) / 1e6;
        double cpuMs = (c1 - c0) / 1e6;
        double speedup = cpuMs / Math.max(gpuMs, 0.001);
        int points = size * size;

        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Benchmark (%d x %d = %,d points): GPU %.2f ms | CPU (1 thread) %.2f ms | %.1fx faster",
                size, size, points, gpuMs, cpuMs, speedup)).withStyle(ChatFormatting.GREEN), false);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  GPU center=%.4f  CPU center=%.4f", gpu[size / 2 * size + size / 2], cpu[size / 2 * size + size / 2]))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int heightmap(CommandContext<CommandSourceStack> ctx, int w, int h, int seed) {
        CommandSourceStack src = ctx.getSource();
        GpuComputeEngine engine = GpuAcceleratorMod.ensureEngine();
        if (engine == null) {
            src.sendFailure(Component.literal("GPU unavailable: " + GpuComputeEngine.lastError()));
            return 0;
        }
        long t0 = System.nanoTime();
        float[] data = engine.heightmap(w, h, 0.02f, 60f, 40f, seed);
        long t1 = System.nanoTime();

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : data) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        String msg = String.format(Locale.ROOT,
                "Heightmap %d x %d (seed %d): min=%.2f max=%.2f center=%.2f  (%.2f ms on GPU)",
                w, h, seed, min, max, data[w / 2 * w + w / 2], (t1 - t0) / 1e6);
        src.sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
