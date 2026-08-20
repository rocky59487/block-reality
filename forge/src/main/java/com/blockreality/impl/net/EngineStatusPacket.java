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

    /** {@code writeUtf}'s cap for {@link #detail}; longer strings make it THROW. */
    private static final int MAX_DETAIL = 256;
    private static final int MAX_STATUS = 32;

    /**
     * The canonical constructor truncates rather than trusting callers: {@code
     * writeUtf(s, n)} does not shorten an overlong string, it throws — and an encoder
     * that throws mid-broadcast disconnects every player in the dimension over one
     * verbose diagnostic (FORGE-2). Diagnostics come from engine internals and from
     * filesystem paths, neither of which respects a length budget.
     */
    public EngineStatusPacket {
        status = clip(status == null ? "" : status, MAX_STATUS);
        detail = clip(detail == null ? "" : detail, MAX_DETAIL);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public static void encode(EngineStatusPacket p, FriendlyByteBuf buf) {
        buf.writeUtf(p.status, MAX_STATUS);
        buf.writeUtf(p.detail, MAX_DETAIL);
    }

    public static EngineStatusPacket decode(FriendlyByteBuf buf) {
        // Bounded reads; readUtf enforces the cap without an exception path of our own.
        return new EngineStatusPacket(buf.readUtf(MAX_STATUS), buf.readUtf(MAX_DETAIL));
    }

    /** Client-only type by FQN, never imported — see {@link StressResultPacket#handle}. */
    public static void handle(EngineStatusPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.blockreality.impl.client.ClientStressState.acceptStatus(p)));
        ctx.get().setPacketHandled(true);
    }
}
