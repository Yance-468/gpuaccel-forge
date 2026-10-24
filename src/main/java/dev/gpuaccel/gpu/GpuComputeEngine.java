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
    private final GpuDeviceInfo deviceInfo;
    private final long maxMemoryBytes;
    private final int computeUnits;

    private int heightmapCalls;
    private long totalNanos;
    private long lastHeightmapNanos;

    private GpuComputeEngine(long context, long queue, long device, long program, long heightmapKernel, GpuDeviceInfo deviceInfo, long maxMemoryBytes, int computeUnits) {
        this.context = context;
        this.queue = queue;
        this.device = device;
        this.program = program;
        this.heightmapKernel = heightmapKernel;
        this.deviceInfo = deviceInfo;
        this.maxMemoryBytes = maxMemoryBytes;
        this.computeUnits = computeUnits;
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
                lastHeightmapNanos
        );
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

                return new GpuComputeEngine(context, queue, dev, program, kernel, info, maxMem, units);
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

    @Override
    public void close() {
        clReleaseKernel(heightmapKernel);
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
