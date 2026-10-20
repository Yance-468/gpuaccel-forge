// GPU heightmap generation via 2D value-noise fBm (6 octaves).
// Every (x, y) column is independent, so the whole grid is embarrassingly parallel
// and maps naturally onto thousands of GPU work-items.

uint hash2(int x, int y, uint seed) {
    uint h = (uint)x * 374761393u + (uint)y * 668265263u + seed * 1274126177u;
    h = (h ^ (h >> 13)) * 1274126177u;
    h = h ^ (h >> 16);
    return h;
}

float vnoise(float x, float y, uint seed) {
    int xi = (int)floor(x);
    int yi = (int)floor(y);
    float xf = x - (float)xi;
    float yf = y - (float)yi;
    float u = xf * xf * (3.0f - 2.0f * xf);
    float v = yf * yf * (3.0f - 2.0f * yf);
    float a = (float)(hash2(xi,     yi,     seed) & 0xFFFFu) / 65535.0f;
    float b = (float)(hash2(xi + 1, yi,     seed) & 0xFFFFu) / 65535.0f;
    float c = (float)(hash2(xi,     yi + 1, seed) & 0xFFFFu) / 65535.0f;
    float d = (float)(hash2(xi + 1, yi + 1, seed) & 0xFFFFu) / 65535.0f;
    return mix(mix(a, b, u), mix(c, d, u), v);
}

float fbm(float x, float y, uint seed) {
    float value = 0.0f;
    float amp = 0.5f;
    float freq = 1.0f;
    for (int i = 0; i < 6; i++) {
        value += amp * vnoise(x * freq, y * freq, seed + (uint)i * 101u);
        freq *= 2.0f;
        amp *= 0.5f;
    }
    return value;
}

__kernel void heightmap(__global float* out, int width, int height,
                        float scale, float baseHeight, float amplitude, uint seed) {
    int gx = get_global_id(0);
    int gy = get_global_id(1);
    if (gx >= width || gy >= height) return;
    float h = fbm((float)gx * scale, (float)gy * scale, seed);
    out[gy * width + gx] = baseHeight + h * amplitude;
}
