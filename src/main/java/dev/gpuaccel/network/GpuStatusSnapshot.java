package dev.gpuaccel.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Immutable snapshot of the server's status (GPU + CPU + memory + world size), serialized to the client.
 */
public record GpuStatusSnapshot(
        boolean engineReady,
        String deviceName,
        String vendor,
        String version,
        long maxMemoryBytes,
        int computeUnits,
        int heightmapCalls,
        long totalNanos,
        long lastHeightmapNanos,
        double busyPercent,
        double systemCpuLoad,
        double processCpuLoad,
        long heapUsedBytes,
        long heapMaxBytes,
        long worldSizeBytes
) {
    public static GpuStatusSnapshot empty() {
        return new GpuStatusSnapshot(false, "等待数据 / Waiting for data", "", "", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0);
    }

    public static GpuStatusSnapshot unavailable(String reason) {
        return new GpuStatusSnapshot(false, reason == null ? "未知 / unknown" : reason, "", "", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0);
    }

    public static GpuStatusSnapshot notAuthorized() {
        return new GpuStatusSnapshot(false, "需要管理员权限 / Requires operator", "", "", 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0);
    }

    public GpuStatusSnapshot withServerStats(double systemCpu, double processCpu, long heapUsed, long heapMax, long worldSize) {
        return new GpuStatusSnapshot(engineReady, deviceName, vendor, version, maxMemoryBytes, computeUnits,
                heightmapCalls, totalNanos, lastHeightmapNanos, busyPercent,
                systemCpu, processCpu, heapUsed, heapMax, worldSize);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(engineReady);
        buf.writeUtf(deviceName);
        buf.writeUtf(vendor);
        buf.writeUtf(version);
        buf.writeLong(maxMemoryBytes);
        buf.writeInt(computeUnits);
        buf.writeInt(heightmapCalls);
        buf.writeLong(totalNanos);
        buf.writeLong(lastHeightmapNanos);
        buf.writeDouble(busyPercent);
        buf.writeDouble(systemCpuLoad);
        buf.writeDouble(processCpuLoad);
        buf.writeLong(heapUsedBytes);
        buf.writeLong(heapMaxBytes);
        buf.writeLong(worldSizeBytes);
    }

    public static GpuStatusSnapshot read(FriendlyByteBuf buf) {
        return new GpuStatusSnapshot(
                buf.readBoolean(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readLong(),
                buf.readInt(),
                buf.readInt(),
                buf.readLong(),
                buf.readLong(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong()
        );
    }
}
