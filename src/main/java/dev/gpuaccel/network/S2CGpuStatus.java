package dev.gpuaccel.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: carries the GPU status snapshot. */
public record S2CGpuStatus(GpuStatusSnapshot snapshot) {
    public static void encode(S2CGpuStatus msg, FriendlyByteBuf buf) {
        msg.snapshot.write(buf);
    }

    public static S2CGpuStatus decode(FriendlyByteBuf buf) {
        return new S2CGpuStatus(GpuStatusSnapshot.read(buf));
    }

    public static void handle(S2CGpuStatus msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> GpuStatusHolder.update(msg.snapshot));
        ctx.setPacketHandled(true);
    }
}
