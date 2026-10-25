// GPU fluid simulation: one step of a 2D cellular automaton that spreads fluid (1=source) to
// adjacent empty cells (0=air). Run repeatedly for a multi-step spread.

__kernel void fluid(__global char* in, __global char* out, int w, int h) {
    int x = get_global_id(0);
    int z = get_global_id(1);
    if (x >= w || z >= h) return;
    int i = z * w + x;
    if (in[i] == 1) { out[i] = 1; return; }
    if (x > 0 && in[i - 1] == 1) { out[i] = 1; return; }
    if (x < w - 1 && in[i + 1] == 1) { out[i] = 1; return; }
    if (z > 0 && in[i - w] == 1) { out[i] = 1; return; }
    if (z < h - 1 && in[i + w] == 1) { out[i] = 1; return; }
    out[i] = 0;
}
