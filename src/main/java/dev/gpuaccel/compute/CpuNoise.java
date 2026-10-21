package dev.gpuaccel.compute;

/**
 * Single-threaded CPU reference implementation of the same heightmap noise used by the
 * OpenCL kernel. The integer-hash value noise is implemented with explicit 32-bit masking
 * so its output is <b>bit-identical</b> to the GPU kernel (useful for testing and as a
 * graceful fallback when no OpenCL device is available).
 */
public final class CpuNoise {
    private CpuNoise() {}

    public static float[] heightmap(int w, int h, float scale, float base, float amp, int seed, int offsetX, int offsetZ) {
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                out[row + x] = base + amp * fbm((offsetX + x) * scale, (offsetZ + y) * scale, seed);
            }
        }
        return out;
    }

    public static float[] heightmap(int w, int h, float scale, float base, float amp, int seed) {
        return heightmap(w, h, scale, base, amp, seed, 0, 0);
    }

    public static float fbm(float x, float y, int seed) {
        float value = 0.0f, amp = 0.5f, freq = 1.0f;
        for (int i = 0; i < 6; i++) {
            value += amp * vnoise(x * freq, y * freq, seed + i * 101);
            freq *= 2.0f;
            amp *= 0.5f;
        }
        return value;
    }

    private static float vnoise(float x, float y, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float xf = x - xi, yf = y - yi;
        float u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
        float a = h(xi, yi, seed), b = h(xi + 1, yi, seed), c = h(xi, yi + 1, seed), d = h(xi + 1, yi + 1, seed);
        return lerp(lerp(a, b, u), lerp(c, d, u), v);
    }

    private static float h(int x, int y, int seed) {
        long n = ((long) x * 374761393L + (long) y * 668265263L + (long) seed * 1274126177L) & 0xFFFFFFFFL;
        n = ((n ^ (n >>> 13)) * 1274126177L) & 0xFFFFFFFFL;
        n = (n ^ (n >>> 16)) & 0xFFFFFFFFL;
        return (n & 0xFFFF) / 65535.0f;
    }

    private static float lerp(float a, float b, float t) { return a + t * (b - a); }
}
