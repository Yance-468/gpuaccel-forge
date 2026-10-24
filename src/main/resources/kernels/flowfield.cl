// GPU flow-field pathfinding: BFS flood-fill from a target cell over a walkability grid,
// then a per-cell direction (1=+x, 2=-x, 3=+z, 4=-z, 0=none) toward the target.
// The grid is small (n*n), so the flood is run as repeated cheap passes from the host.

__kernel void ff_reset(__global int* dist, int n) {
    int i = get_global_id(0);
    if (i >= n * n) return;
    dist[i] = 1000000;
}

__kernel void ff_target(__global int* dist, int targetIdx) {
    dist[targetIdx] = 0;
}

__kernel void ff_flood(__global int* dist, __global char* walkable, int n) {
    int x = get_global_id(0);
    int z = get_global_id(1);
    if (x >= n || z >= n) return;
    int i = z * n + x;
    if (!walkable[i]) return;
    int best = dist[i];
    if (x > 0 && dist[i - 1] + 1 < best) best = dist[i - 1] + 1;
    if (x < n - 1 && dist[i + 1] + 1 < best) best = dist[i + 1] + 1;
    if (z > 0 && dist[i - n] + 1 < best) best = dist[i - n] + 1;
    if (z < n - 1 && dist[i + n] + 1 < best) best = dist[i + n] + 1;
    if (best < dist[i]) dist[i] = best;
}

__kernel void ff_flow(__global int* dist, __global char* walkable, __global uchar* out, int n) {
    int x = get_global_id(0);
    int z = get_global_id(1);
    if (x >= n || z >= n) return;
    int i = z * n + x;
    uchar dir = 0;
    if (walkable[i] && dist[i] < 1000000) {
        int best = dist[i];
        if (x > 0 && dist[i - 1] < best) { best = dist[i - 1]; dir = 2; }
        if (x < n - 1 && dist[i + 1] < best) { best = dist[i + 1]; dir = 1; }
        if (z > 0 && dist[i - n] < best) { best = dist[i - n]; dir = 4; }
        if (z < n - 1 && dist[i + n] < best) { best = dist[i + n]; dir = 3; }
    }
    out[i] = dir;
}
