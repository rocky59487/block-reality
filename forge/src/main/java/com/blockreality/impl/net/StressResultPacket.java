package com.blockreality.impl.net;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.BucklingState;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.UnassignedBlocks;
import com.blockreality.api.UnassignedReason;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.WorldRevision;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The drawable half of an analysis, sent to the client.
 *
 * <p>Channel 10 carries immutable beam/shell samples, complete per-element cell lists,
 * independent verdict flags and beam diagnostic end forces. It carries no mechanical field.
 *
 * <h2>Decoding never throws — and never launders</h2>
 * Two rules, and they answer different attacks:
 *
 * <ul>
 *   <li><strong>Never throws.</strong> Forge kicks a player whose packet decoder throws,
 *       so every read — including the netty {@code IndexOutOfBoundsException} a truncated
 *       buffer raises — is caught here and turned into an <em>invalid</em> packet that
 *       the handler drops (#39).
 *   <li><strong>Never launders.</strong> The old decoder washed NaN into 0.0, unknown
 *       enum ordinals into NONE and hostile counts into clamped ones, which turned a
 *       corrupt frame into a confident healthy-looking overlay (#40/#50). Now any
 *       off-schema value rejects the WHOLE packet: the client keeps its previous good
 *       state and logs why, which is the same fail-closed posture the engine wire has.
 * </ul>
 *
 * <p>Channel 10 forwards global and element flags independently of their f64 values.
 */
public final class StressResultPacket {

    /** Candidate caps are shared with the total byte/cell/station policy. Omission travels in the packet. */
    private static final int MAX_MEMBERS = DisplayDelivery.MAX_MEMBERS;
    /** Facet candidates, including the governing facet when present. */
    private static final int MAX_SHELLS = DisplayDelivery.MAX_SHELLS;


    private final DisplayDelivery delivery;
    private final int governing;
    private final String governingKind;
    private final boolean governingOmitted;
    private final boolean valid;
    private final String invalidReason;

    private final long revision;
    /** Which dimension this result describes; the client drops a mismatch (#41). */
    private final String dimension;
    private final boolean singular;
    private final double maxDc;
    /** Supplied capacity verdict; independent of the display number. */
    private final boolean overCapacity;
    private final int islands;
    private final int singularIslands;
    private final double bucklingFactor;
    /** Supplied buckling verdict; its boundary belongs to the originating engine. */
    private final boolean bucklingCritical;
    /**
     * What the buckling number is, in one field instead of a factor plus a flag.
     *
     * <p>A factor of 0 cannot distinguish "skipped for size" from "no positive mode" from
     * "nothing eligible", and until N18 the wire carried only the first of those, as a
     * boolean. {@link BucklingState#DISABLED_BY_SCALE} is substituted here and nowhere
     * else: the engine cannot know it was the host that declined to ask.
     */
    private final BucklingState bucklingState;
    /**
     * How many blocks fell out of the model, per reason.
     *
     * <p>Counts, not coordinates. A fully supported raft can list fourteen thousand blocks
     * and the HUD only ever needs the tally; {@code /br status} runs on the server and
     * reads the coordinates straight off the result.
     */
    private final int[] unassignedByReason;
    /** Solved totals BEFORE truncation, so the HUD can say what it is not showing (#42). */
    private final int totalMembers;
    private final int totalShells;
    /**
     * Tracked blocks the SERVER could not read, that were touching what it did send.
     *
     * <p>Zero is a fact, not an absence: the gather ran and the model was whole. Nonzero
     * means the engine answered about a structure with a piece missing — the answer is
     * not wrong so much as about a different building (#74, N14-b).
     */
    private final int truncatedBlocks;
    /** Ids of members whose input was cut; their verdict and colour are withheld (N14-c). */
    private final Set<Integer> withheldMembers;
    /** Ids of shell facets whose input was cut. */
    private final Set<Integer> withheldShells;
    private final List<MemberSnapshot> members;
    private final List<ShellSnapshot> shells;

    private StressResultPacket(boolean valid, String invalidReason,
                               long revision, String dimension, boolean singular,
                               double maxDc, boolean overCapacity,
                               int islands, int singularIslands,
                               double bucklingFactor, boolean bucklingCritical,
                               BucklingState bucklingState, int[] unassignedByReason,
                               int totalMembers, int totalShells,
                               int truncatedBlocks,
                               Set<Integer> withheldMembers, Set<Integer> withheldShells,
                               List<MemberSnapshot> members, List<ShellSnapshot> shells,
                               int governing, String governingKind, boolean governingOmitted, DisplayDelivery delivery) {
        this.delivery = delivery;
        this.governing = governing;
        this.governingKind = governingKind;
        this.governingOmitted = governingOmitted;
        this.valid = valid;
        this.invalidReason = invalidReason;
        this.revision = revision;
        // Clipped, not trusted: writeUtf THROWS on an overlong string, and an encoder
        // that throws mid-broadcast disconnects every player over one long datapack
        // dimension id (the FORGE-2 failure shape; v0.3a review §3-6).
        this.dimension = clip(dimension == null ? "" : dimension, 256);
        this.singular = singular;
        this.maxDc = maxDc;
        this.overCapacity = overCapacity;
        this.islands = islands;
        this.singularIslands = singularIslands;
        this.bucklingFactor = bucklingFactor;
        this.bucklingCritical = bucklingCritical;
        this.bucklingState = bucklingState == null ? BucklingState.UNKNOWN : bucklingState;
        this.unassignedByReason = normaliseReasons(unassignedByReason);
        this.totalMembers = totalMembers;
        this.totalShells = totalShells;
        this.truncatedBlocks = Math.max(0, truncatedBlocks);
        this.withheldMembers = withheldMembers == null ? Set.of() : Set.copyOf(withheldMembers);
        this.withheldShells = withheldShells == null ? Set.of() : Set.copyOf(withheldShells);
        this.members = members;
        this.shells = shells;
    }

    private static StressResultPacket invalid(String reason) {
        return new StressResultPacket(false, reason, 0, "", false, 0, false, 0, 0, 0, false,
                BucklingState.UNKNOWN, null, 0, 0, 0, Set.of(), Set.of(), List.of(), List.of(), -1, "", false, null);
    }

    /** A per-reason tally sized to this build of the enum, whatever the caller passed. */
    private static int[] normaliseReasons(int[] in) {
        int[] out = new int[UnassignedReason.values().length];
        if (in != null) {
            for (int i = 0; i < out.length && i < in.length; i++) out[i] = Math.max(0, in[i]);
        }
        return out;
    }

    public static StressResultPacket of(AnalysisResult r, String dimension, boolean bucklingSkipped) {
        return of(r, dimension, bucklingSkipped, Set.of(), Set.of(), 0);
    }

    public static StressResultPacket of(AnalysisResult r, String dimension, boolean bucklingSkipped,
                                        Set<Integer> withheldMembers, Set<Integer> withheldShells,
                                        int truncatedBlocks) {
        if (!r.ok()) return invalid("analysis failed: " + r.diagnostic());
        DisplayDelivery delivery = DisplayDelivery.prepare(r,
                withheldMembers == null ? Set.of() : withheldMembers, withheldShells == null ? Set.of() : withheldShells);
        List<MemberSnapshot> m = delivery.members();
        List<ShellSnapshot> s = delivery.shells();
        boolean omitted = omitted(r.governingKind(), r.governing(), m, s);
        // The one place DISABLED_BY_SCALE can be said. The engine was asked not to run
        // the screen and answered accordingly; only this side knows the reason was size.
        BucklingState state = bucklingSkipped ? BucklingState.DISABLED_BY_SCALE : r.bucklingState();
        int[] byReason = new int[UnassignedReason.values().length];
        for (UnassignedBlocks g : r.unassigned()) {
            byReason[g.reason().ordinal()] += g.blocks().size();
        }
        return new StressResultPacket(true, "",
                r.revision().value(), dimension, r.singular(),
                r.maxDc(), r.overCapacity(),
                r.islands(), r.singularIslands(),
                r.bucklingFactor(), r.bucklingCritical(), state, byReason,
                r.members().size(), r.shells().size(),
                truncatedBlocks, selectedWithheld(withheldMembers, m.stream().map(MemberSnapshot::id).toList()),
                selectedWithheld(withheldShells, s.stream().map(ShellSnapshot::id).toList()),
                m, s, r.governing(), r.governingKind(), omitted, delivery);
    }

    private static Set<Integer> selectedWithheld(Set<Integer> all, List<Integer> selected) {
        if (all == null || all.isEmpty()) return Set.of();
        Set<Integer> out = new java.util.HashSet<>();
        for (int id : selected) if (all.contains(id)) out.add(id);
        return out;
    }

    private static boolean omitted(String kind, int id, List<MemberSnapshot> m, List<ShellSnapshot> s) {
        return "member".equals(kind) ? m.stream().noneMatch(v -> v.id() == id)
                : "shell".equals(kind) && s.stream().noneMatch(v -> v.id() == id);
    }
    public int governing() { return governing; }
    public String governingKind() { return governingKind; }
    public boolean governingOmitted() { return governingOmitted; }
    /** A received summary remains analysis even if no complete element fits the display budget. */
    public boolean hasSummary() { return valid && revision >= 0; }
    public boolean allMechanism() { return singular && islands > 0 && singularIslands == islands; }

    /** False when decoding failed; the handler must drop the packet, not render it. */
    public boolean valid() { return valid; }

    public String invalidReason() { return invalidReason; }

    public long revision() { return revision; }

    public String dimension() { return dimension; }

    public boolean singular() { return singular; }

    public double maxDc() { return maxDc; }

    /** The server's double-precision verdict; the client never re-derives it. */
    public boolean overCapacity() { return overCapacity; }

    public int islands() { return islands; }

    public int singularIslands() { return singularIslands; }

    /** Smallest supplied linear-buckling factor. Read bucklingCritical() for the verdict. */
    public double bucklingFactor() { return bucklingFactor; }

    /** The server's double-precision verdict; the client never re-derives it. */
    public boolean bucklingCritical() { return bucklingCritical; }

    public BucklingState bucklingState() { return bucklingState; }

    /** Kept for the callers that only ask the old question. */
    public boolean bucklingSkipped() { return bucklingState == BucklingState.DISABLED_BY_SCALE; }

    /** Blocks that fell out of the model with this reason. */
    public int unassignedCount(UnassignedReason reason) {
        return unassignedByReason[reason.ordinal()];
    }

    /** Blocks that fell out of the model, all reasons together. */
    public int unassignedTotal() {
        int t = 0;
        for (int c : unassignedByReason) t += c;
        return t;
    }

    /** Tracked blocks the server could not read that touched this request (#74, N14-b). */
    public int truncatedBlocks() { return truncatedBlocks; }

    /** Ids of members whose verdict is withheld because their input was cut (N14-c). */
    public Set<Integer> withheldMembers() { return withheldMembers; }

    public Set<Integer> withheldShells() { return withheldShells; }

    public int totalMembers() { return totalMembers; }

    public int totalShells() { return totalShells; }

    public boolean membersTruncated() { return totalMembers > members.size(); }

    public boolean shellsTruncated() { return totalShells > shells.size(); }

    public List<MemberSnapshot> members() { return members; }

    public List<ShellSnapshot> shells() { return shells; }

    // ---------------------------------------------------------------- encode
    //
    // Shared beam/shell samples and independent global verdicts; no force reconstruction.
    public static void encode(StressResultPacket p, FriendlyByteBuf buf) {
        if (!p.valid) throw new IllegalArgumentException("cannot encode invalid analysis packet");
        int start = buf.writerIndex();
        buf.writeVarLong(p.revision);
        buf.writeUtf(p.dimension, 256);
        buf.writeBoolean(p.singular);
        buf.writeDouble(p.maxDc);
        buf.writeBoolean(p.overCapacity);
        buf.writeVarInt(Math.max(0, p.islands));
        buf.writeVarInt(Math.max(0, p.singularIslands));
        buf.writeDouble(p.bucklingFactor);
        buf.writeBoolean(p.bucklingCritical);
        buf.writeByte(p.bucklingState.ordinal());
        // Fixed length: both ends come out of the same jar, so the enum cannot differ
        // between them, and a length prefix would only add a number to disagree about.
        for (int c : p.unassignedByReason) buf.writeVarInt(c);
        buf.writeVarInt(p.truncatedBlocks);
        buf.writeVarInt(Math.max(0, p.totalMembers));
        buf.writeVarInt(Math.max(0, p.totalShells));
        buf.writeVarInt(Math.min(p.members.size(), MAX_MEMBERS));

        if (p.delivery != null) p.delivery.writeMembers(buf);
        else for (int i = 0; i < p.members.size() && i < MAX_MEMBERS; i++) {
            MemberPacketCodec.write(buf, p.members.get(i), p.withheldMembers.contains(p.members.get(i).id()));
        }

        buf.writeVarInt(Math.min(p.shells.size(), MAX_SHELLS));
        if (p.delivery != null) p.delivery.writeShells(buf);
        else for (int i = 0; i < p.shells.size() && i < MAX_SHELLS; i++) {
            ShellSnapshot s = p.shells.get(i);
            ShellPacketCodec.write(buf, s, p.withheldShells.contains(s.id()));
        }
        buf.writeByte("member".equals(p.governingKind) ? 1 : "shell".equals(p.governingKind) ? 2 : 0);
        buf.writeVarInt(p.governing);
        buf.writeBoolean(p.governingOmitted);
        if (buf.writerIndex() - start > DisplayDelivery.MAX_PACKET_BYTES)
            throw new IllegalStateException("display header exceeded reserved budget");
    }

    /**
     * Truncates rather than throws.
     *
     * <p>{@code writeUtf(s, max)} throws when {@code s} is longer, and this encode runs
     * inside the broadcast loop — one over-long token from an engine that is not the one
     * this build ships would take out the send to every player, not just the drawing of
     * one member. {@code AnalysisUpdatePacket} already guards its two strings this way; the
     * two element tokens did not (PR26_REVIEW ATK-10 / DF-11). Tokens come from the
     * engine, so this should never fire; a guard that never fires is the point.
     */
    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---------------------------------------------------------------- decode
    /** Off-schema content inside a frame; caught at the decode boundary. */
    private static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Bad(String why) { super(why); }
    }

    public static StressResultPacket decode(FriendlyByteBuf buf) {
        try {
            return decodeStrict(buf);
        } catch (RuntimeException e) {
            // Truncation (netty IndexOutOfBounds), a hostile count, NaN where a number
            // belongs — one packet, one rejection. Consuming the leftovers keeps the
            // channel's own bookkeeping quiet; the handler drops the packet.
            if (buf.readableBytes() > 0) buf.skipBytes(buf.readableBytes());
            return invalid(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static StressResultPacket decodeStrict(FriendlyByteBuf buf) {
        if (buf.readableBytes() > DisplayDelivery.MAX_PACKET_BYTES) throw new Bad("display frame budget exceeded");
        var budget = new DisplayDelivery.ReadBudget();
        long revision = buf.readVarLong();
        if (revision < 0) throw new Bad("negative revision");
        String dimension = buf.readUtf(256);
        boolean singular = buf.readBoolean();
        double maxDc = finite(buf.readDouble(), "maxDc");
        if (maxDc < 0) throw new Bad("negative maxDc");
        boolean overCapacity = buf.readBoolean();
        int islands = count(buf.readVarInt(), Integer.MAX_VALUE, "islands");
        int singularIslands = count(buf.readVarInt(), Integer.MAX_VALUE, "singularIslands");
        double bucklingFactor = finite(buf.readDouble(), "bucklingFactor");
        if (bucklingFactor < 0) throw new Bad("negative bucklingFactor");
        boolean bucklingCritical = buf.readBoolean();
        int stateOrdinal = buf.readByte();
        if (stateOrdinal < 0 || stateOrdinal >= BucklingState.values().length) {
            throw new Bad("unknown bucklingState ordinal " + stateOrdinal);
        }
        BucklingState bucklingState = BucklingState.values()[stateOrdinal];
        // f64 preserves even subnormal factors. Contradictions are rejected, never repaired.
        // A factor and a state that disagree is a contradiction, not a schema: every
        // state but COMPUTED means the number was never produced.
        if (bucklingState.hasFactor() != (bucklingFactor > 0)) {
            throw new Bad("bucklingState " + bucklingState + " beside factor " + bucklingFactor);
        }
        int[] unassignedByReason = new int[UnassignedReason.values().length];
        for (int i = 0; i < unassignedByReason.length; i++) {
            unassignedByReason[i] = count(buf.readVarInt(), Integer.MAX_VALUE, "unassigned count");
        }
        int truncatedBlocks = count(buf.readVarInt(), Integer.MAX_VALUE, "truncatedBlocks");
        int totalMembers = count(buf.readVarInt(), Integer.MAX_VALUE, "totalMembers");
        int totalShells = count(buf.readVarInt(), Integer.MAX_VALUE, "totalShells");
        int nMembers = count(buf.readVarInt(), MAX_MEMBERS, "members");
        if (nMembers > totalMembers || singularIslands > islands) throw new Bad("summary count contradiction");

        Set<Integer> withheldMembers = new java.util.LinkedHashSet<>();
        Set<Integer> withheldShells = new java.util.LinkedHashSet<>();
        Set<Integer> memberIds = new java.util.HashSet<>(), shellIds = new java.util.HashSet<>();
        List<MemberSnapshot> members = new ArrayList<>(nMembers);
        for (int i = 0; i < nMembers; i++) {
            MemberPacketCodec.Entry entry = MemberPacketCodec.read(buf, budget);
            if (!memberIds.add(entry.member().id())) throw new Bad("duplicate member id");
            members.add(entry.member());
            if (entry.withheld()) withheldMembers.add(entry.member().id());
        }

        int nShells = count(buf.readVarInt(), MAX_SHELLS, "shells");
        if (nShells > totalShells) throw new Bad("shell total smaller than sent count");
        List<ShellSnapshot> shells = new ArrayList<>(nShells);
        for (int i = 0; i < nShells; i++) {
            ShellPacketCodec.Entry entry = ShellPacketCodec.read(buf, budget);
            if (entry.shell().id() < 0 || !shellIds.add(entry.shell().id())) throw new Bad("invalid/duplicate shell id");
            shells.add(entry.shell());
            if (entry.withheld()) withheldShells.add(entry.shell().id());
        }

        int kindCode = count(buf.readUnsignedByte(), 2, "governing kind");
        String governingKind = kindCode == 1 ? "member" : kindCode == 2 ? "shell" : "";
        int governing = buf.readVarInt();
        boolean governingOmitted = buf.readBoolean();
        if (governing < -1 || (kindCode == 0 ? governing != -1 : governing < 0)
                || governingOmitted != omitted(governingKind, governing, members, shells))
            throw new Bad("governing delivery contradiction");

        // Nothing was left out, yet something claims its input was cut. The server sets
        // both from one computation, so this cannot happen honestly — and a withheld
        // flag with no reason behind it would grey out a member the player could not
        // find an explanation for. Same posture as the buckling contradiction above.
        if (truncatedBlocks == 0 && !(withheldMembers.isEmpty() && withheldShells.isEmpty())) {
            throw new Bad("elements withheld with no truncated blocks");
        }

        if (buf.readableBytes() > 0) {
            // Consumed exactly, or not this schema. Bytes past the end mean the two
            // sides disagree about the layout, and a disagreement that still parses
            // is the dangerous kind.
            throw new Bad(buf.readableBytes() + " bytes left after the packet");
        }

        return new StressResultPacket(true, "", revision, dimension, singular,
                maxDc, overCapacity, islands, singularIslands,
                bucklingFactor, bucklingCritical, bucklingState, unassignedByReason,
                totalMembers, totalShells,
                truncatedBlocks, withheldMembers, withheldShells, members, shells,
                governing, governingKind, governingOmitted, null);
    }

    /** Out-of-range counts reject the packet: a count past the cap is not this schema. */
    private static int count(int n, int max, String what) {
        if (n < 0 || n > max) throw new Bad("implausible " + what + " count " + n);
        return n;
    }

    /**
     * A NaN or infinity is corruption, not a value: it rejects the packet rather than
     * being washed to 0.0 (#40/#50). The one legitimate producer of these fields is the
     * engine wire, which is itself finite-checked, so a well-behaved server can never
     * hit this.
     */
    private static double finite(double f, String what) {
        if (!Double.isFinite(f)) throw new Bad(what + " is not finite");
        return f;
    }

    public WorldRevision worldRevision() { return new WorldRevision(revision); }
}
