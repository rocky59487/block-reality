package com.blockreality.impl.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * "The world has moved past what you are looking at."
 *
 * <p>Sent when a dimension's revision advances (a block placed, a load changed) so the
 * client can mark its current overlay <em>stale</em> until the next result lands. Without
 * this signal the client has no way to know its picture is out of date, and a stale
 * stress map drawn as current is exactly the display-track violation invariant 5 lists:
 * the player sees numbers for a structure that no longer exists (INV-4).
 *
 * <p>Tiny and unthrottled beyond one-per-revision-batch-per-tick: it carries a dimension
 * name and a varlong.
 *
 * @param dimension the dimension whose revision moved, as a resource-location string
 * @param revision  the revision the world is now at
 */
public record AnalysisPendingPacket(String dimension, long revision) {

    public AnalysisPendingPacket {
        // Clipped, not trusted: writeUtf THROWS on an overlong string, so one long
        // datapack dimension id would disconnect every player it broadcasts to.
        if (dimension == null) dimension = "";
        if (dimension.length() > 256) dimension = dimension.substring(0, 255) + "…";
    }

    public static void encode(AnalysisPendingPacket p, FriendlyByteBuf buf) {
        buf.writeUtf(p.dimension, 256);
        buf.writeVarLong(p.revision);
    }

    public static AnalysisPendingPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        long revision = Math.max(0, buf.readVarLong());
        return new AnalysisPendingPacket(dimension, revision);
    }

    /** Client-only type by FQN, never imported — see {@link StressResultPacket#handle}. */
    public static void handle(AnalysisPendingPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.blockreality.impl.client.ClientStressState.acceptPending(p)));
        ctx.get().setPacketHandled(true);
    }
}
