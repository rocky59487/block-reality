package com.blockreality.impl.net;

import com.blockreality.core.engine.NativeGameRuntime;
import com.blockreality.impl.BlockRealityMod;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Channel 11 envelope for every analysis result and notice. A source is bound only by bootstrap. */
public record AnalysisUpdatePacket(String dimension, UUID sourceId, long sequence, long worldRevision,
        boolean bootstrap, Kind kind, NativeGameRuntime.Status engineStatus, String detail,
        StressResultPacket result, boolean valid, String invalidReason) {
    public enum Kind { RESULT, PENDING, EMPTY, OFF, MODEL_REFUSED, ENGINE_UNAVAILABLE }
    public static final int MAX_BYTES = DisplayDelivery.MAX_PACKET_BYTES;
    private static final int MAX_DIMENSION = 256, MAX_DETAIL = 256;

    public static AnalysisUpdatePacket of(String dimension, UUID sourceId, long sequence, long worldRevision,
            boolean bootstrap, Kind kind, NativeGameRuntime.Status status, String detail, StressResultPacket result) {
        String message = detail == null ? "" : detail;
        if (message.length() > MAX_DETAIL) {
            int end = MAX_DETAIL - 1;
            if (Character.isHighSurrogate(message.charAt(end - 1))) end--;
            message = message.substring(0, end) + "…";
        }
        var packet = new AnalysisUpdatePacket(dimension, sourceId, sequence, worldRevision,
                bootstrap, kind, status, message, result, true, "");
        try { validate(packet); return packet; }
        catch (RuntimeException e) { return invalid(e.getMessage()); }
    }

    private static AnalysisUpdatePacket invalid(String reason) {
        return new AnalysisUpdatePacket("", new UUID(0, 0), 0, 0, false, Kind.ENGINE_UNAVAILABLE,
                NativeGameRuntime.Status.DISABLED, "", null, false, reason == null ? "invalid update" : reason);
    }

    private static void validate(AnalysisUpdatePacket p) {
        if (p.dimension == null || p.dimension.length() > MAX_DIMENSION
                || ResourceLocation.tryParse(p.dimension) == null
                || !ResourceLocation.tryParse(p.dimension).toString().equals(p.dimension))
            throw new IllegalArgumentException("invalid analysis dimension");
        if (p.sourceId == null || p.sequence <= 0 || p.worldRevision < 0 || p.kind == null || p.engineStatus == null)
            throw new IllegalArgumentException("invalid analysis source/sequence/revision/kind");
        if (p.detail == null || p.detail.length() > MAX_DETAIL) throw new IllegalArgumentException("invalid detail");
        if (p.kind == Kind.RESULT) {
            if (p.result == null || !p.result.valid() || !p.result.dimension().equals(p.dimension)
                    || p.result.revision() < 0 || p.result.revision() > p.worldRevision || !p.detail.isEmpty())
                throw new IllegalArgumentException("result does not match its envelope");
        } else if (p.result != null) throw new IllegalArgumentException("notice carries a result");
    }

    public static void encode(AnalysisUpdatePacket p, FriendlyByteBuf buf) {
        if (!p.valid) throw new IllegalArgumentException("cannot encode invalid update");
        validate(p);
        int start = buf.writerIndex();
        buf.writeUtf(p.dimension, MAX_DIMENSION); buf.writeUUID(p.sourceId);
        buf.writeVarLong(p.sequence); buf.writeVarLong(p.worldRevision); buf.writeBoolean(p.bootstrap);
        buf.writeByte(p.kind.ordinal()); buf.writeByte(p.engineStatus.ordinal()); buf.writeUtf(p.detail, MAX_DETAIL);
        if (p.result != null) StressResultPacket.encode(p.result, buf);
        if (buf.writerIndex() - start > MAX_BYTES) throw new IllegalStateException("analysis envelope exceeded byte budget");
    }

    public static AnalysisUpdatePacket decode(FriendlyByteBuf buf) {
        try {
            if (buf.readableBytes() > MAX_BYTES) throw new IllegalArgumentException("oversized analysis update");
            String dimension = buf.readUtf(MAX_DIMENSION); UUID source = buf.readUUID();
            long sequence = buf.readVarLong(), revision = buf.readVarLong();
            int bootstrap = buf.readUnsignedByte();
            if (bootstrap > 1) throw new IllegalArgumentException("invalid bootstrap flag");
            int kind = buf.readUnsignedByte(), status = buf.readUnsignedByte();
            if (kind >= Kind.values().length || status >= NativeGameRuntime.Status.values().length)
                throw new IllegalArgumentException("unknown analysis kind/status");
            String detail = buf.readUtf(MAX_DETAIL);
            StressResultPacket result = kind == Kind.RESULT.ordinal() ? StressResultPacket.decode(buf) : null;
            if (buf.isReadable()) throw new IllegalArgumentException("trailing analysis update bytes");
            return of(dimension, source, sequence, revision, bootstrap != 0,
                    Kind.values()[kind], NativeGameRuntime.Status.values()[status], detail, result);
        } catch (RuntimeException e) {
            if (buf.isReadable()) buf.skipBytes(buf.readableBytes());
            return invalid(e.getMessage());
        }
    }

    public static void handle(AnalysisUpdatePacket p, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (p.valid) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.blockreality.impl.client.ClientStressState.acceptUpdate(p, context.getNetworkManager())));
        else BlockRealityMod.LOG.warn("dropping malformed analysis update: {}", p.invalidReason);
        context.setPacketHandled(true);
    }
}
