// GPU explosion calculation: compute a blast-damage field around an explosion center.
// Distance-based falloff (a simplified ray-cast model); the host filters by block blast resistance.

__kernel void explosion(__global float* out, int r, float power) {
    int gx = get_global_id(0);
    int gy = get_global_id(1);
    int gz = get_global_id(2);
    int n = 2 * r + 1;
    if (gx >= n || gy >= n || gz >= n) return;
    float x = (float)(gx - r);
    float y = (float)(gy - r);
    float z = (float)(gz - r);
    float d = sqrt(x * x + y * y + z * z);
    float damage = 0.0f;
    if (d <= (float)r && d > 0.001f) {
        damage = power * (1.0f - d / (float)r);
    }
    out[gz * n * n + gy * n + gx] = damage;
}
