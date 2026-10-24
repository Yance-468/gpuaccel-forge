package dev.gpuaccel.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: ask the server to send the current GPU status. */
public record C2SGpuStatusRequest() {
    public static void encode(C2SGpuStatusRequest msg, FriendlyByteBuf buf) {}

    public static C2SGpuStatusRequest decode(FriendlyByteBuf buf) {
        return new C2SGpuStatusRequest();
    }

    public static void handle(C2SGpuStatusRequest msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender != null) {
                GpuNetwork.sendStatusToClient(sender);
            }
        });
        ctx.setPacketHandled(true);
    }
}
