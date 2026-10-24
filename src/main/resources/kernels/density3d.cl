// GPU 3D terrain density via 3D value-noise fBm (6 octaves), plus a cave carve.
// Every voxel (x, y-sample, z) is independent -> embarrassingly parallel.
// Output is one byte per voxel: 0=air, 1=stone, 5=water, 6=bedrock.

uint hash3(int x, int y, int z, uint seed) {
    uint h = (uint)x * 374761393u + (uint)y * 668265263u + (uint)z * 2246822519u + seed * 1274126177u;
    h = (h ^ (h >> 13)) * 1274126177u;
    h = h ^ (h >> 16);
    return h;
}

float vnoise3(float x, float y, float z, uint seed) {
    int xi = (int)floor(x);
    int yi = (int)floor(y);
    int zi = (int)floor(z);
    float xf = x - (float)xi;
    float yf = y - (float)yi;
    float zf = z - (float)zi;
    float u = xf * xf * (3.0f - 2.0f * xf);
    float v = yf * yf * (3.0f - 2.0f * yf);
    float w = zf * zf * (3.0f - 2.0f * zf);
    float c000 = (float)(hash3(xi,     yi,     zi,     seed) & 0xFFFFu) / 65535.0f;
    float c100 = (float)(hash3(xi + 1, yi,     zi,     seed) & 0xFFFFu) / 65535.0f;
    float c010 = (float)(hash3(xi,     yi + 1, zi,     seed) & 0xFFFFu) / 65535.0f;
    float c110 = (float)(hash3(xi + 1, yi + 1, zi,     seed) & 0xFFFFu) / 65535.0f;
    float c001 = (float)(hash3(xi,     yi,     zi + 1, seed) & 0xFFFFu) / 65535.0f;
    float c101 = (float)(hash3(xi + 1, yi,     zi + 1, seed) & 0xFFFFu) / 65535.0f;
    float c011 = (float)(hash3(xi,     yi + 1, zi + 1, seed) & 0xFFFFu) / 65535.0f;
    float c111 = (float)(hash3(xi + 1, yi + 1, zi + 1, seed) & 0xFFFFu) / 65535.0f;
    float x00 = mix(c000, c100, u);
    float x10 = mix(c010, c110, u);
    float x01 = mix(c001, c101, u);
    float x11 = mix(c011, c111, u);
    float y0 = mix(x00, x10, v);
    float y1 = mix(x01, x11, v);
    return mix(y0, y1, w);
}

float fbm3(float x, float y, float z, uint seed) {
    float value = 0.0f;
    float amp = 0.5f;
    float freq = 1.0f;
    for (int i = 0; i < 6; i++) {
        value += amp * vnoise3(x * freq, y * freq, z * freq, seed + (uint)i * 101u);
        freq *= 2.0f;
        amp *= 0.5f;
    }
    return value;
}

__kernel void density(__global uchar* out, int width, int depth, int height,
                      float scale, float base, float amp, float caveScale, float caveAmp,
                      uint seed, int offsetX, int offsetZ, int minY, int seaLevel) {
    int gx = get_global_id(0);
    int gz = get_global_id(1);
    int gy = get_global_id(2);
    if (gx >= width || gz >= depth || gy >= height) return;
    int wx = offsetX + gx;
    int wz = offsetZ + gz;
    int wy = minY + gy;

    float terrain = base + amp * fbm3((float)wx * scale, (float)wy * scale, (float)wz * scale, seed) - (float)wy;
    float cave = fbm3((float)wx * caveScale, (float)wy * caveScale, (float)wz * caveScale, seed + 7919u);
    float density = terrain - cave * caveAmp;

    uchar block = 0;
    if (density > 0.0f) {
        block = 1;
        float oreC = fbm3((float)wx * 0.12f, (float)wy * 0.12f, (float)wz * 0.12f, seed + 12345u);
        float oreI = fbm3((float)wx * 0.12f, (float)wy * 0.12f, (float)wz * 0.12f, seed + 23456u);
        float oreG = fbm3((float)wx * 0.12f, (float)wy * 0.12f, (float)wz * 0.12f, seed + 34567u);
        float oreD = fbm3((float)wx * 0.12f, (float)wy * 0.12f, (float)wz * 0.12f, seed + 45678u);
        if (oreC > 0.80f && wy >= 0 && wy <= 128) block = 11;
        else if (oreI > 0.88f && wy >= -32 && wy <= 32) block = 12;
        else if (oreG > 0.95f && wy <= 16) block = 13;
        else if (oreD > 0.985f && wy <= -48) block = 14;
    } else {
        if (wy <= seaLevel && wy >= -48) block = 5;
        else if (wy < -48) block = 10;
    }
    if (wy <= minY + 1) block = 6;
    out[gy * (width * depth) + gz * width + gx] = block;
}
