// GPU per-mob steering (boids + flow field): for each mob, combine the flow direction toward
// the target with separation (away from neighbors) and cohesion (toward the local center of mass).
// The O(N^2) neighbor loop is parallelized across N work-items.

__kernel void boids(__global float* pos, __global float* flow, __global float* out, int n) {
    int i = get_global_id(0);
    if (i >= n) return;
    float ix = pos[i * 3], iy = pos[i * 3 + 1], iz = pos[i * 3 + 2];
    float sx = 0.0f, sy = 0.0f, sz = 0.0f;
    float cx = 0.0f, cy = 0.0f, cz = 0.0f;
    int neighbors = 0;
    for (int j = 0; j < n; j++) {
        if (j == i) continue;
        float dx = ix - pos[j * 3];
        float dy = iy - pos[j * 3 + 1];
        float dz = iz - pos[j * 3 + 2];
        float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < 16.0f && d2 > 0.0001f) {
            float inv = 1.0f / (d2 + 0.01f);
            sx += dx * inv;
            sy += dy * inv;
            sz += dz * inv;
            cx += pos[j * 3];
            cy += pos[j * 3 + 1];
            cz += pos[j * 3 + 2];
            neighbors++;
        }
    }
    if (neighbors > 0) {
        cx = cx / neighbors - ix;
        cy = cy / neighbors - iy;
        cz = cz / neighbors - iz;
    }
    float fx = flow[i * 3], fy = flow[i * 3 + 1], fz = flow[i * 3 + 2];
    out[i * 3]     = fx * 1.0f + sx * 0.6f + cx * 0.15f;
    out[i * 3 + 1] = fy * 1.0f + sy * 0.3f + cy * 0.05f;
    out[i * 3 + 2] = fz * 1.0f + sz * 0.6f + cz * 0.15f;
}
