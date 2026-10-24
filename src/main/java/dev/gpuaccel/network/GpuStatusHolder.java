package dev.gpuaccel.network;

/** Holds the latest GPU status snapshot received from the server (updated on the client). */
public final class GpuStatusHolder {
    private static volatile GpuStatusSnapshot latest = GpuStatusSnapshot.empty();

    public static GpuStatusSnapshot latest() {
        return latest;
    }

    public static void update(GpuStatusSnapshot snapshot) {
        latest = snapshot;
    }

    private GpuStatusHolder() {}
}
