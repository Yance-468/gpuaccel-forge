package dev.gpuaccel.network;

import dev.gpuaccel.GpuAcceleratorMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Network channel for the GPU status GUI. */
public final class GpuNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(GpuAcceleratorMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int nextId = 0;

    public static void register() {
        CHANNEL.registerMessage(nextId++, C2SGpuStatusRequest.class,
                C2SGpuStatusRequest::encode, C2SGpuStatusRequest::decode, C2SGpuStatusRequest::handle);
        CHANNEL.registerMessage(nextId++, S2CGpuStatus.class,
                S2CGpuStatus::encode, S2CGpuStatus::decode, S2CGpuStatus::handle);
    }

    public static void sendStatusToClient(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new S2CGpuStatus(GpuAcceleratorMod.status(player.getServer())));
    }

    public static void sendNotAuthorized(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new S2CGpuStatus(GpuStatusSnapshot.notAuthorized()));
    }

    private GpuNetwork() {}
}
