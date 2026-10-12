package com.blockreality.impl.net;

import com.blockreality.impl.BlockRealityMod;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Channel-12 finite qualification delivery: the authoritative W3 analysis basis —
 * world revision, history sequence, convergence, per-island native statuses, the
 * material-history availability census and arc scalars — carried verbatim from the
 * engine's reply. The client displays; it never derives.
 *
 * <p>Absence is data, not silence: an empty island list means "not assessed", and an
 * unreported load factor is a flag, not a zero.
 */
public record FiniteQualificationPacket(String dimension, long worldRevision, long historySequence,
        boolean fullyConverged, boolean loadFactorReported, double loadFactor,
        List<Island> islands, boolean availabilityReported, int memberProfiles, int memberMaterial,
        int memberRetained, int shellProfiles, int shellMaterial, int shellRetained,
        boolean arcReported, double arcRadius, double arcResidual,
        boolean valid, String invalidReason) {

    /** One native island verdict row; {@code status} is the raw wire code (0..4 known). */
    public record Island(long status, long flags, String reason) { }

    public static final int MAX_BYTES = 64 << 10;
    private static final int MAX_DIMENSION = 256, MAX_REASON = 128, MAX_ISLANDS = 4096;

    public static FiniteQualificationPacket of(String dimension, long worldRevision, long historySequence,
            boolean fullyConverged, boolean loadFactorReported, double loadFactor,
            List<Island> islands, boolean availabilityReported, int memberProfiles, int memberMaterial,
            int memberRetained, int shellProfiles, int shellMaterial, int shellRetained,
            boolean arcReported, double arcRadius, double arcResidual) {
        var packet = new FiniteQualificationPacket(dimension, worldRevision, historySequence,
                fullyConverged, loadFactorReported, loadFactor, List.copyOf(islands),
                availabilityReported, memberProfiles, memberMaterial, memberRetained,
                shellProfiles, shellMaterial, shellRetained, arcReported, arcRadius, arcResidual,
                true, "");
        try { validate(packet); return packet; }
        catch (RuntimeException e) { return invalid(e.getMessage()); }
    }

    private static FiniteQualificationPacket invalid(String reason) {
        return new FiniteQualificationPacket("", 0, 0, false, false, 0, List.of(),
                false, 0, 0, 0, 0, 0, 0, false, 0, 0, false,
                reason == null ? "invalid qualification" : reason);
    }

    private static void validate(FiniteQualificationPacket p) {
        if (p.dimension == null || p.dimension.isEmpty() || p.dimension.length() > MAX_DIMENSION
                || ResourceLocation.tryParse(p.dimension) == null
                || !ResourceLocation.tryParse(p.dimension).toString().equals(p.dimension))
            throw new IllegalArgumentException("invalid qualification dimension");
        if (p.worldRevision < 0 || p.historySequence < 0)
            throw new IllegalArgumentException("invalid qualification basis");
        if (p.loadFactorReported && !Double.isFinite(p.loadFactor))
            throw new IllegalArgumentException("invalid load factor");
        if (p.islands == null || p.islands.size() > MAX_ISLANDS)
            throw new IllegalArgumentException("invalid island table");
        for (Island island : p.islands) {
            if (island == null || island.status < 0 || island.status > Integer.MAX_VALUE
                    || island.flags < 0 || island.flags > 7)
                throw new IllegalArgumentException("invalid island row");
            if (island.reason == null || island.reason.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_REASON)
                throw new IllegalArgumentException("invalid island reason");
        }
        // material (&4) and retained (&2 without &4) are disjoint subsets of the
        // profile rows: retained > material is legal, the bound is the union.
        if (p.memberProfiles < 0 || p.memberMaterial < 0 || p.memberMaterial > p.memberProfiles
                || p.memberRetained < 0 || p.memberMaterial + p.memberRetained > p.memberProfiles
                || p.shellProfiles < 0 || p.shellMaterial < 0 || p.shellMaterial > p.shellProfiles
                || p.shellRetained < 0 || p.shellMaterial + p.shellRetained > p.shellProfiles)
            throw new IllegalArgumentException("invalid availability census");
        if (p.arcReported && (!(p.arcRadius > 0) || !Double.isFinite(p.arcRadius)
                || !Double.isFinite(p.arcResidual) || p.arcResidual < 0 || p.arcResidual > 1e-10))
            throw new IllegalArgumentException("invalid arc scalars");
    }

    public static void encode(FiniteQualificationPacket p, FriendlyByteBuf buf) {
        if (!p.valid) throw new IllegalArgumentException("cannot encode invalid qualification");
        validate(p);
        int start = buf.writerIndex();
        buf.writeUtf(p.dimension, MAX_DIMENSION);
        buf.writeVarLong(p.worldRevision); buf.writeVarLong(p.historySequence);
        buf.writeBoolean(p.fullyConverged);
        buf.writeBoolean(p.loadFactorReported);
        if (p.loadFactorReported) buf.writeDouble(p.loadFactor);
        buf.writeVarInt(p.islands.size());
        for (Island island : p.islands) {
            buf.writeVarInt((int) island.status); buf.writeVarInt((int) island.flags);
            buf.writeUtf(island.reason, MAX_REASON);
        }
        buf.writeBoolean(p.availabilityReported);
        buf.writeVarInt(p.memberProfiles); buf.writeVarInt(p.memberMaterial); buf.writeVarInt(p.memberRetained);
        buf.writeVarInt(p.shellProfiles); buf.writeVarInt(p.shellMaterial); buf.writeVarInt(p.shellRetained);
        buf.writeBoolean(p.arcReported);
        if (p.arcReported) { buf.writeDouble(p.arcRadius); buf.writeDouble(p.arcResidual); }
        if (buf.writerIndex() - start > MAX_BYTES)
            throw new IllegalStateException("qualification exceeded byte budget");
    }

    public static FiniteQualificationPacket decode(FriendlyByteBuf buf) {
        try {
            if (buf.readableBytes() > MAX_BYTES) throw new IllegalArgumentException("oversized qualification");
            String dimension = buf.readUtf(MAX_DIMENSION);
            long worldRevision = buf.readVarLong(), historySequence = buf.readVarLong();
            boolean fullyConverged = buf.readBoolean();
            boolean loadFactorReported = buf.readBoolean();
            double loadFactor = loadFactorReported ? buf.readDouble() : 0;
            int islandCount = buf.readVarInt();
            if (islandCount < 0 || islandCount > MAX_ISLANDS)
                throw new IllegalArgumentException("invalid island count");
            var islands = new ArrayList<Island>(islandCount);
            for (int i = 0; i < islandCount; i++)
                islands.add(new Island(buf.readVarInt() & 0xFFFFFFFFL, buf.readVarInt() & 0xFFFFFFFFL,
                        buf.readUtf(MAX_REASON)));
            boolean availabilityReported = buf.readBoolean();
            int memberProfiles = buf.readVarInt(), memberMaterial = buf.readVarInt(),
                    memberRetained = buf.readVarInt();
            int shellProfiles = buf.readVarInt(), shellMaterial = buf.readVarInt(),
                    shellRetained = buf.readVarInt();
            boolean arcReported = buf.readBoolean();
            double arcRadius = arcReported ? buf.readDouble() : 0;
            double arcResidual = arcReported ? buf.readDouble() : 0;
            if (buf.isReadable()) throw new IllegalArgumentException("trailing qualification bytes");
            return of(dimension, worldRevision, historySequence, fullyConverged,
                    loadFactorReported, loadFactor, islands, availabilityReported,
                    memberProfiles, memberMaterial, memberRetained,
                    shellProfiles, shellMaterial, shellRetained,
                    arcReported, arcRadius, arcResidual);
        } catch (RuntimeException e) {
            if (buf.isReadable()) buf.skipBytes(buf.readableBytes());
            return invalid(e.getMessage());
        }
    }

    public static void handle(FiniteQualificationPacket p, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (p.valid) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.blockreality.impl.client.ClientFiniteState.accept(p, context.getNetworkManager())));
        else BlockRealityMod.LOG.warn("dropping malformed finite qualification: {}", p.invalidReason);
        context.setPacketHandled(true);
    }
}
