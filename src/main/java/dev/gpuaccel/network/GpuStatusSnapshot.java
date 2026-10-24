package dev.gpuaccel.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Immutable snapshot of the server's GPU status, serialized over the network to the client.
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
        double busyPercent
) {
    public static GpuStatusSnapshot empty() {
        return new GpuStatusSnapshot(false, "等待数据 / Waiting for data", "", "", 0, 0, 0, 0, 0, 0.0);
    }

    public static GpuStatusSnapshot unavailable(String reason) {
        return new GpuStatusSnapshot(false, reason == null ? "未知 / unknown" : reason, "", "", 0, 0, 0, 0, 0, 0.0);
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
                buf.readDouble()
        );
    }
}
