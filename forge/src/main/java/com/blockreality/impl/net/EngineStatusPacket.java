package com.blockreality.impl.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Tells the client the engine is unavailable, and why.
 *
 * <p>Silence would be worse than a message. A player whose overlay simply stopped updating
 * has no way to tell a missing sidecar from a structure that happens to be unstressed, and
 * would reasonably conclude the mod is broken.
 */
public record EngineStatusPacket(String status, String detail) {

    public static void encode(EngineStatusPacket p, FriendlyByteBuf buf) {
        buf.writeUtf(p.status, 32);
        buf.writeUtf(p.detail, 256);
    }

    public static EngineStatusPacket decode(FriendlyByteBuf buf) {
        // Bounded reads; readUtf enforces the cap without an exception path of our own.
        return new EngineStatusPacket(buf.readUtf(32), buf.readUtf(256));
    }

    /** Client-only type by FQN, never imported — see {@link StressResultPacket#handle}. */
    public static void handle(EngineStatusPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.blockreality.impl.client.ClientStressState.acceptStatus(p)));
        ctx.get().setPacketHandled(true);
    }
}
