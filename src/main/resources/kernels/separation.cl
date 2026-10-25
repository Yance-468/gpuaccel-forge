// GPU entity separation: for each entity, compute a push-away vector from overlapping neighbors.
// O(N^2) pairwise check is parallelized across N work-items.

__kernel void separation(__global float* pos, __global float* out, int n) {
    int i = get_global_id(0);
    if (i >= n) return;
    float ix = pos[i * 3], iy = pos[i * 3 + 1], iz = pos[i * 3 + 2];
    float sx = 0.0f, sy = 0.0f, sz = 0.0f;
    for (int j = 0; j < n; j++) {
        if (j == i) continue;
        float dx = ix - pos[j * 3];
        float dy = iy - pos[j * 3 + 1];
        float dz = iz - pos[j * 3 + 2];
        float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < 1.0f && d2 > 0.0001f) {
            float d = sqrt(d2);
            float push = 1.0f - d;
            sx += (dx / d) * push;
            sy += (dy / d) * push;
            sz += (dz / d) * push;
        }
    }
    out[i * 3] = sx;
    out[i * 3 + 1] = sy;
    out[i * 3 + 2] = sz;
}
