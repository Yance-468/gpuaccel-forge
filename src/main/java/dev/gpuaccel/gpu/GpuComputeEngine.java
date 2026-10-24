package dev.gpuaccel.gpu;

import dev.gpuaccel.network.GpuStatusSnapshot;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opencl.CL10.*;

/**
 * Thin, self-contained OpenCL wrapper built on LWJGL. It discovers a GPU, compiles the
 * heightmap kernel, and runs massively-parallel noise generation off the server's CPU thread.
 *
 * <p>Not thread-safe: a single engine instance is used from the server thread only.</p>
 */
public final class GpuComputeEngine implements AutoCloseable {

    private static volatile boolean openClReady;
    private static volatile String lastError;

    private final long context;
    private final long queue;
    private final long device;
    private final long program;
    private final long heightmapKernel;
    private final long densityKernel;
    private final long ffResetKernel;
    private final long ffFloodKernel;
    private final long ffFlowKernel;
    private final long ffTargetKernel;
    private final GpuDeviceInfo deviceInfo;
    private final long maxMemoryBytes;
    private final int computeUnits;

    private int heightmapCalls;
    private long totalNanos;
    private long lastHeightmapNanos;
    private final long startNanos;

    private GpuComputeEngine(long context, long queue, long device, long program, long heightmapKernel, long densityKernel, long ffResetKernel, long ffFloodKernel, long ffFlowKernel, long ffTargetKernel, GpuDeviceInfo deviceInfo, long maxMemoryBytes, int computeUnits) {
        this.context = context;
        this.queue = queue;
        this.device = device;
        this.program = program;
        this.heightmapKernel = heightmapKernel;
        this.densityKernel = densityKernel;
        this.ffResetKernel = ffResetKernel;
        this.ffFloodKernel = ffFloodKernel;
        this.ffFlowKernel = ffFlowKernel;
        this.ffTargetKernel = ffTargetKernel;
        this.deviceInfo = deviceInfo;
        this.maxMemoryBytes = maxMemoryBytes;
        this.computeUnits = computeUnits;
        this.startNanos = System.nanoTime();
    }

    public GpuDeviceInfo deviceInfo() {
        return deviceInfo;
    }

    /** Builds a snapshot of the current GPU status for reporting to clients. */
    public GpuStatusSnapshot snapshot() {
        return new GpuStatusSnapshot(
                true,
                deviceInfo.name(),
                deviceInfo.vendor(),
                deviceInfo.version(),
                maxMemoryBytes,
                computeUnits,
                heightmapCalls,
                totalNanos,
                lastHeightmapNanos,
                busyPercent(),
                0.0, 0.0, 0L, 0L, 0L
        );
    }

    /** Estimated GPU busy fraction (0-100) since the engine started: total compute time / elapsed time. */
    public double busyPercent() {
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed <= 0) return 0.0;
        return Math.min(100.0, (double) totalNanos / elapsed * 100.0);
    }

    /** Human-readable reason for the last OpenCL failure, or {@code null}. */
    public static String lastError() {
        return lastError;
    }

    private static synchronized void ensureOpenCl() {
        if (openClReady) return;
        try {
            // Bundle our own LWJGL core native and point LWJGL at it, so this works even on a
            // bare dedicated server that never set up LWJGL natives.
            LwjglNatives.prepare();
            Configuration.OPENCL_EXPLICIT_INIT.set(true);
            CL.create();
            openClReady = true;
            lastError = null;
        } catch (Throwable t) {
            lastError = t.toString() + (t.getCause() != null ? " (cause: " + t.getCause() + ")" : "");
            throw new IllegalStateException("OpenCL init failed", t);
        }
    }

    /** Lists every OpenCL device (GPU/CPU/accelerator) across all platforms. */
    public static List<GpuDeviceInfo> enumerateDevices() {
        List<GpuDeviceInfo> result = new ArrayList<>();
        try {
            ensureOpenCl();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer num = stack.mallocInt(1);
                check(clGetPlatformIDs(null, num));
                PointerBuffer platforms = stack.mallocPointer(num.get(0));
                check(clGetPlatformIDs(platforms, (IntBuffer) null));
                for (int i = 0; i < num.get(0); i++) {
                    long p = platforms.get(i);
                    IntBuffer dnum = stack.mallocInt(1);
                    check(clGetDeviceIDs(p, CL_DEVICE_TYPE_ALL, null, dnum));
                    PointerBuffer devs = stack.mallocPointer(dnum.get(0));
                    check(clGetDeviceIDs(p, CL_DEVICE_TYPE_ALL, devs, (IntBuffer) null));
                    for (int j = 0; j < dnum.get(0); j++) {
                        long d = devs.get(j);
                        result.add(new GpuDeviceInfo(
                                info(d, CL_DEVICE_NAME, true),
                                info(d, CL_DEVICE_VENDOR, true),
                                info(d, CL_DEVICE_VERSION, true),
                                deviceTypeName(d)));
                    }
                }
            }
        } catch (Throwable ignored) {
            // handled via lastError()
        }
        return result;
    }

    /** Creates the engine, or returns {@code null} (and records {@link #lastError()}) on failure. */
    public static GpuComputeEngine tryCreate(String kernelSource) {
        try {
            ensureOpenCl();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long dev = findDevice(stack, CL_DEVICE_TYPE_GPU);
                if (dev == 0) dev = findDevice(stack, CL_DEVICE_TYPE_ALL);
                if (dev == 0) {
                    lastError = "no OpenCL device";
                    return null;
                }

                GpuDeviceInfo info = new GpuDeviceInfo(
                        info(dev, CL_DEVICE_NAME, true),
                        info(dev, CL_DEVICE_VENDOR, true),
                        info(dev, CL_DEVICE_VERSION, true),
                        deviceTypeName(dev));

                long maxMem = deviceLong(stack, dev, CL_DEVICE_GLOBAL_MEM_SIZE);
                int units = deviceInt(stack, dev, CL_DEVICE_MAX_COMPUTE_UNITS);

                IntBuffer err = stack.mallocInt(1);
                long context = clCreateContext((PointerBuffer) null, dev, null, MemoryUtil.NULL, err);
                check(err.get(0));
                long queue = clCreateCommandQueue(context, dev, 0, err);
                check(err.get(0));

                long program = clCreateProgramWithSource(context, kernelSource, err);
                check(err.get(0));
                int be = clBuildProgram(program, dev, "", null, MemoryUtil.NULL);
                if (be != CL_SUCCESS) {
                    String log = buildLog(program, dev);
                    clReleaseProgram(program);
                    clReleaseCommandQueue(queue);
                    clReleaseContext(context);
                    lastError = "kernel build failed: " + log;
                    return null;
                }
                long kernel = clCreateKernel(program, "heightmap", err);
                check(err.get(0));
                long densityKernel = clCreateKernel(program, "density", err);
                check(err.get(0));
                long ffReset = clCreateKernel(program, "ff_reset", err);
                check(err.get(0));
                long ffTarget = clCreateKernel(program, "ff_target", err);
                check(err.get(0));
                long ffFlood = clCreateKernel(program, "ff_flood", err);
                check(err.get(0));
                long ffFlow = clCreateKernel(program, "ff_flow", err);
                check(err.get(0));

                return new GpuComputeEngine(context, queue, dev, program, kernel, densityKernel, ffReset, ffFlood, ffFlow, ffTarget, info, maxMem, units);
            }
        } catch (Throwable t) {
            // ensureOpenCl() already records a detailed reason; keep it if present.
            if (lastError == null) {
                lastError = t.getCause() != null ? t.getCause().toString() : t.toString();
            }
            return null;
        }
    }

    /** Generates a heightmap entirely on the GPU, at the given world-coordinate offset. */
    public synchronized float[] heightmap(int w, int h, float scale, float base, float amp, int seed, int offsetX, int offsetZ) {
        long start = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            FloatBuffer out = MemoryUtil.memAllocFloat(w * h);
            long buffer = clCreateBuffer(context, CL_MEM_WRITE_ONLY, (long) w * h * 4L, err);
            check(err.get(0));
            try {
                check(clSetKernelArg1p(heightmapKernel, 0, buffer));
                check(clSetKernelArg1i(heightmapKernel, 1, w));
                check(clSetKernelArg1i(heightmapKernel, 2, h));
                check(clSetKernelArg1f(heightmapKernel, 3, scale));
                check(clSetKernelArg1f(heightmapKernel, 4, base));
                check(clSetKernelArg1f(heightmapKernel, 5, amp));
                check(clSetKernelArg1i(heightmapKernel, 6, seed));
                check(clSetKernelArg1i(heightmapKernel, 7, offsetX));
                check(clSetKernelArg1i(heightmapKernel, 8, offsetZ));
                PointerBuffer gws = stack.mallocPointer(2).put(w).put(h).flip();
                check(clEnqueueNDRangeKernel(queue, heightmapKernel, 2, null, gws, null, null, null));
                check(clFinish(queue));
                check(clEnqueueReadBuffer(queue, buffer, true, 0, out, null, null));
                float[] result = new float[w * h];
                out.get(result);
                return result;
            } finally {
                clReleaseMemObject(buffer);
                MemoryUtil.memFree(out);
            }
        } finally {
            long elapsed = System.nanoTime() - start;
            heightmapCalls++;
            totalNanos += elapsed;
            lastHeightmapNanos = elapsed;
        }
    }

    /** Convenience overload with zero offset (used by /gpuaccel benchmark and heightmap). */
    public float[] heightmap(int w, int h, float scale, float base, float amp, int seed) {
        return heightmap(w, h, scale, base, amp, seed, 0, 0);
    }

    /** Generates a 3D terrain density field (one byte per voxel) entirely on the GPU. */
    public synchronized byte[] density3d(int w, int d, int hSamples, float scale, float base, float amp, float caveScale, float caveAmp, int seed, int offsetX, int offsetZ, int minY, int seaLevel) {
        long start = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            ByteBuffer out = MemoryUtil.memAlloc(w * d * hSamples);
            long buffer = clCreateBuffer(context, CL_MEM_WRITE_ONLY, (long) w * d * hSamples, err);
            check(err.get(0));
            try {
                check(clSetKernelArg1p(densityKernel, 0, buffer));
                check(clSetKernelArg1i(densityKernel, 1, w));
                check(clSetKernelArg1i(densityKernel, 2, d));
                check(clSetKernelArg1i(densityKernel, 3, hSamples));
                check(clSetKernelArg1f(densityKernel, 4, scale));
                check(clSetKernelArg1f(densityKernel, 5, base));
                check(clSetKernelArg1f(densityKernel, 6, amp));
                check(clSetKernelArg1f(densityKernel, 7, caveScale));
                check(clSetKernelArg1f(densityKernel, 8, caveAmp));
                check(clSetKernelArg1i(densityKernel, 9, seed));
                check(clSetKernelArg1i(densityKernel, 10, offsetX));
                check(clSetKernelArg1i(densityKernel, 11, offsetZ));
                check(clSetKernelArg1i(densityKernel, 12, minY));
                check(clSetKernelArg1i(densityKernel, 13, seaLevel));
                PointerBuffer gws = stack.mallocPointer(3).put(w).put(d).put(hSamples).flip();
                check(clEnqueueNDRangeKernel(queue, densityKernel, 3, null, gws, null, null, null));
                check(clFinish(queue));
                check(clEnqueueReadBuffer(queue, buffer, true, 0, out, null, null));
                byte[] result = new byte[w * d * hSamples];
                out.get(result);
                return result;
            } finally {
                clReleaseMemObject(buffer);
                MemoryUtil.memFree(out);
            }
        } finally {
            long elapsed = System.nanoTime() - start;
            heightmapCalls++;
            totalNanos += elapsed;
            lastHeightmapNanos = elapsed;
        }
    }

    /** Computes a flow field (per-cell direction toward a target) on the GPU via BFS flood-fill. */
    public synchronized byte[] flowField(byte[] walkable, int n, int targetX, int targetZ) {
        long start = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            int total = n * n;
            ByteBuffer walk = MemoryUtil.memAlloc(total);
            walk.put(walkable).flip();
            ByteBuffer outHost = MemoryUtil.memAlloc(total);

            long walkDev = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, walk, err);
            check(err.get(0));
            long distDev = clCreateBuffer(context, CL_MEM_READ_WRITE, (long) total * 4L, err);
            check(err.get(0));
            long outDev = clCreateBuffer(context, CL_MEM_WRITE_ONLY, total, err);
            check(err.get(0));
            try {
                PointerBuffer gws1 = stack.mallocPointer(1).put(total).flip();
                PointerBuffer gws2 = stack.mallocPointer(2).put(n).put(n).flip();

                check(clSetKernelArg1p(ffResetKernel, 0, distDev));
                check(clSetKernelArg1i(ffResetKernel, 1, n));
                check(clEnqueueNDRangeKernel(queue, ffResetKernel, 1, null, gws1, null, null, null));

                check(clSetKernelArg1p(ffTargetKernel, 0, distDev));
                check(clSetKernelArg1i(ffTargetKernel, 1, targetZ * n + targetX));
                check(clEnqueueNDRangeKernel(queue, ffTargetKernel, 1, null, gws1, null, null, null));

                check(clSetKernelArg1p(ffFloodKernel, 0, distDev));
                check(clSetKernelArg1p(ffFloodKernel, 1, walkDev));
                check(clSetKernelArg1i(ffFloodKernel, 2, n));
                int iterations = 2 * n + 4;
                for (int it = 0; it < iterations; it++) {
                    check(clEnqueueNDRangeKernel(queue, ffFloodKernel, 2, null, gws2, null, null, null));
                }

                check(clSetKernelArg1p(ffFlowKernel, 0, distDev));
                check(clSetKernelArg1p(ffFlowKernel, 1, walkDev));
                check(clSetKernelArg1p(ffFlowKernel, 2, outDev));
                check(clSetKernelArg1i(ffFlowKernel, 3, n));
                check(clEnqueueNDRangeKernel(queue, ffFlowKernel, 2, null, gws2, null, null, null));
                check(clFinish(queue));

                check(clEnqueueReadBuffer(queue, outDev, true, 0, outHost, null, null));
                byte[] result = new byte[total];
                outHost.get(result);
                return result;
            } finally {
                clReleaseMemObject(walkDev);
                clReleaseMemObject(distDev);
                clReleaseMemObject(outDev);
                MemoryUtil.memFree(walk);
                MemoryUtil.memFree(outHost);
            }
        } finally {
            long elapsed = System.nanoTime() - start;
            heightmapCalls++;
            totalNanos += elapsed;
            lastHeightmapNanos = elapsed;
        }
    }

    @Override
    public void close() {
        clReleaseKernel(heightmapKernel);
        clReleaseKernel(densityKernel);
        clReleaseKernel(ffResetKernel);
        clReleaseKernel(ffFloodKernel);
        clReleaseKernel(ffFlowKernel);
        clReleaseKernel(ffTargetKernel);
        clReleaseProgram(program);
        clReleaseCommandQueue(queue);
        clReleaseContext(context);
    }

    // ---- helpers ----

    private static long findDevice(MemoryStack stack, long type) {
        IntBuffer pnum = stack.mallocInt(1);
        check(clGetPlatformIDs(null, pnum));
        PointerBuffer platforms = stack.mallocPointer(pnum.get(0));
        check(clGetPlatformIDs(platforms, (IntBuffer) null));
        for (int i = 0; i < pnum.get(0); i++) {
            long p = platforms.get(i);
            IntBuffer dnum = stack.mallocInt(1);
            check(clGetDeviceIDs(p, type, null, dnum));
            if (dnum.get(0) > 0) {
                PointerBuffer devs = stack.mallocPointer(dnum.get(0));
                check(clGetDeviceIDs(p, type, devs, (IntBuffer) null));
                return devs.get(0);
            }
        }
        return 0;
    }

    private static String deviceTypeName(long d) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer val = stack.mallocLong(1);
            check(clGetDeviceInfo(d, CL_DEVICE_TYPE, val, null));
            long type = val.get(0);
            if ((type & CL_DEVICE_TYPE_GPU) != 0) return "GPU";
            if ((type & CL_DEVICE_TYPE_CPU) != 0) return "CPU";
            if ((type & CL_DEVICE_TYPE_ACCELERATOR) != 0) return "ACCELERATOR";
            return "OTHER";
        }
    }

    private static long deviceLong(MemoryStack stack, long dev, int param) {
        LongBuffer val = stack.mallocLong(1);
        check(clGetDeviceInfo(dev, param, val, null));
        return val.get(0);
    }

    private static int deviceInt(MemoryStack stack, long dev, int param) {
        IntBuffer val = stack.mallocInt(1);
        check(clGetDeviceInfo(dev, param, val, null));
        return val.get(0);
    }

    private static String info(long obj, int param, boolean device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            int err = device ? clGetDeviceInfo(obj, param, (ByteBuffer) null, size)
                             : clGetPlatformInfo(obj, param, (ByteBuffer) null, size);
            check(err);
            int bytes = (int) size.get(0);
            ByteBuffer buf = stack.malloc(bytes);
            err = device ? clGetDeviceInfo(obj, param, buf, null)
                         : clGetPlatformInfo(obj, param, buf, null);
            check(err);
            byte[] arr = new byte[Math.max(0, bytes - 1)];
            buf.get(arr);
            return new String(arr, StandardCharsets.UTF_8);
        }
    }

    private static String buildLog(long program, long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            check(clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, size));
            int bytes = (int) size.get(0);
            ByteBuffer buf = stack.malloc(bytes);
            check(clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, buf, null));
            byte[] arr = new byte[Math.max(0, bytes - 1)];
            buf.get(arr);
            return new String(arr, StandardCharsets.UTF_8);
        }
    }

    private static void check(int err) {
        if (err != CL_SUCCESS) throw new IllegalStateException("OpenCL error code " + err);
    }
}
