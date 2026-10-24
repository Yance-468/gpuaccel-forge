// GPU 3D flow-field pathfinding: BFS flood-fill over a 3D walkability grid (nx * ny * nz),
// then a per-voxel direction toward the target.
// Directions: 0=none, 1=+x, 2=-x, 3=+z, 4=-z, 5=+x+z, 6=-x-z, 7=+x-z, 8=-x+z, 9=+y, 10=-y.

__kernel void ff_reset(__global int* dist, int total) {
    int i = get_global_id(0);
    if (i >= total) return;
    dist[i] = 1000000;
}

__kernel void ff_target(__global int* dist, int targetIdx) {
    dist[targetIdx] = 0;
}

__kernel void ff_flood(__global int* dist, __global char* walkable, int nx, int ny, int nz) {
    int x = get_global_id(0);
    int z = get_global_id(1);
    int y = get_global_id(2);
    if (x >= nx || z >= nz || y >= ny) return;
    int i = y * (nx * nz) + z * nx + x;
    if (!walkable[i]) return;
    int best = dist[i];
    int xz = nx * nz;
    if (x > 0 && dist[i - 1] + 1 < best) best = dist[i - 1] + 1;
    if (x < nx - 1 && dist[i + 1] + 1 < best) best = dist[i + 1] + 1;
    if (z > 0 && dist[i - nx] + 1 < best) best = dist[i - nx] + 1;
    if (z < nz - 1 && dist[i + nx] + 1 < best) best = dist[i + nx] + 1;
    if (x > 0 && z > 0 && dist[i - 1 - nx] + 1 < best) best = dist[i - 1 - nx] + 1;
    if (x < nx - 1 && z > 0 && dist[i + 1 - nx] + 1 < best) best = dist[i + 1 - nx] + 1;
    if (x > 0 && z < nz - 1 && dist[i - 1 + nx] + 1 < best) best = dist[i - 1 + nx] + 1;
    if (x < nx - 1 && z < nz - 1 && dist[i + 1 + nx] + 1 < best) best = dist[i + 1 + nx] + 1;
    if (y > 0 && dist[i - xz] + 1 < best) best = dist[i - xz] + 1;
    if (y < ny - 1 && dist[i + xz] + 1 < best) best = dist[i + xz] + 1;
    if (best < dist[i]) dist[i] = best;
}

__kernel void ff_flow(__global int* dist, __global char* walkable, __global uchar* out, int nx, int ny, int nz) {
    int x = get_global_id(0);
    int z = get_global_id(1);
    int y = get_global_id(2);
    if (x >= nx || z >= nz || y >= ny) return;
    int i = y * (nx * nz) + z * nx + x;
    uchar dir = 0;
    if (walkable[i] && dist[i] < 1000000) {
        int best = dist[i];
        int xz = nx * nz;
        if (x > 0 && dist[i - 1] < best) { best = dist[i - 1]; dir = 2; }
        if (x < nx - 1 && dist[i + 1] < best) { best = dist[i + 1]; dir = 1; }
        if (z > 0 && dist[i - nx] < best) { best = dist[i - nx]; dir = 4; }
        if (z < nz - 1 && dist[i + nx] < best) { best = dist[i + nx]; dir = 3; }
        if (x < nx - 1 && z < nz - 1 && dist[i + 1 + nx] < best) { best = dist[i + 1 + nx]; dir = 5; }
        if (x > 0 && z > 0 && dist[i - 1 - nx] < best) { best = dist[i - 1 - nx]; dir = 6; }
        if (x < nx - 1 && z > 0 && dist[i + 1 - nx] < best) { best = dist[i + 1 - nx]; dir = 7; }
        if (x > 0 && z < nz - 1 && dist[i - 1 + nx] < best) { best = dist[i - 1 + nx]; dir = 8; }
        if (y < ny - 1 && dist[i + xz] < best) { best = dist[i + xz]; dir = 9; }
        if (y > 0 && dist[i - xz] < best) { best = dist[i - xz]; dir = 10; }
    }
    out[i] = dir;
}
