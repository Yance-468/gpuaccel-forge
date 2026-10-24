package dev.gpuaccel.compute;

/**
 * Single-threaded CPU reference implementation of the same noise used by the OpenCL kernels
 * (2D heightmap + 3D density). The integer-hash value noise uses explicit 32-bit masking so
 * its output is bit-identical to the GPU kernels (useful as a graceful fallback when no
 * OpenCL device is available).
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

    public static byte[] density3d(int w, int d, int hSamples, float scale, float base, float amp,
                                   float caveScale, float caveAmp, int seed, int offsetX, int offsetZ,
                                   int minY, int seaLevel) {
        byte[] out = new byte[w * d * hSamples];
        for (int gy = 0; gy < hSamples; gy++) {
            int wy = minY + gy;
            int yPlane = gy * (w * d);
            for (int gz = 0; gz < d; gz++) {
                int wz = offsetZ + gz;
                int row = yPlane + gz * w;
                for (int gx = 0; gx < w; gx++) {
                    int wx = offsetX + gx;
                    float terrain = base + amp * fbm3(wx * scale, wy * scale, wz * scale, seed) - wy;
                    float cave = fbm3(wx * caveScale, wy * caveScale, wz * caveScale, seed + 7919);
                    float density = terrain - cave * caveAmp;
                    byte block = 0;
                    if (density > 0.0f) {
                        block = 1;
                        float oreC = fbm3(wx * 0.12f, wy * 0.12f, wz * 0.12f, seed + 12345);
                        float oreI = fbm3(wx * 0.12f, wy * 0.12f, wz * 0.12f, seed + 23456);
                        float oreG = fbm3(wx * 0.12f, wy * 0.12f, wz * 0.12f, seed + 34567);
                        float oreD = fbm3(wx * 0.12f, wy * 0.12f, wz * 0.12f, seed + 45678);
                        if (oreC > 0.80f && wy >= 0 && wy <= 128) block = 11;
                        else if (oreI > 0.88f && wy >= -32 && wy <= 32) block = 12;
                        else if (oreG > 0.95f && wy <= 16) block = 13;
                        else if (oreD > 0.985f && wy <= -48) block = 14;
                    } else {
                        if (wy <= seaLevel && wy >= -48) block = 5;
                        else if (wy < -48) block = 10;
                    }
                    if (wy <= minY + 1) block = 6;
                    out[row + gx] = block;
                }
            }
        }
        return out;
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

    public static float fbm3(float x, float y, float z, int seed) {
        float value = 0.0f, amp = 0.5f, freq = 1.0f;
        for (int i = 0; i < 6; i++) {
            value += amp * vnoise3(x * freq, y * freq, z * freq, seed + i * 101);
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

    private static float vnoise3(float x, float y, float z, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        float xf = x - xi, yf = y - yi, zf = z - zi;
        float u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf), w = zf * zf * (3 - 2 * zf);
        float c000 = n3(xi, yi, zi, seed), c100 = n3(xi + 1, yi, zi, seed);
        float c010 = n3(xi, yi + 1, zi, seed), c110 = n3(xi + 1, yi + 1, zi, seed);
        float c001 = n3(xi, yi, zi + 1, seed), c101 = n3(xi + 1, yi, zi + 1, seed);
        float c011 = n3(xi, yi + 1, zi + 1, seed), c111 = n3(xi + 1, yi + 1, zi + 1, seed);
        float x00 = lerp(c000, c100, u), x10 = lerp(c010, c110, u);
        float x01 = lerp(c001, c101, u), x11 = lerp(c011, c111, u);
        float y0 = lerp(x00, x10, v), y1 = lerp(x01, x11, v);
        return lerp(y0, y1, w);
    }

    private static float h(int x, int y, int seed) {
        long n = ((long) x * 374761393L + (long) y * 668265263L + (long) seed * 1274126177L) & 0xFFFFFFFFL;
        n = ((n ^ (n >>> 13)) * 1274126177L) & 0xFFFFFFFFL;
        n = (n ^ (n >>> 16)) & 0xFFFFFFFFL;
        return (n & 0xFFFF) / 65535.0f;
    }

    private static float n3(int x, int y, int z, int seed) {
        return (h3(x, y, z, seed) & 0xFFFF) / 65535.0f;
    }

    private static long h3(int x, int y, int z, int seed) {
        long n = ((long) x * 374761393L + (long) y * 668265263L + (long) z * 2246822519L + (long) seed * 1274126177L) & 0xFFFFFFFFL;
        n = ((n ^ (n >>> 13)) * 1274126177L) & 0xFFFFFFFFL;
        n = (n ^ (n >>> 16)) & 0xFFFFFFFFL;
        return n;
    }

    private static float lerp(float a, float b, float t) { return a + t * (b - a); }
}
