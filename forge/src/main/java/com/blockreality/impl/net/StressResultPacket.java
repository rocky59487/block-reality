package com.blockreality.impl.net;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.BucklingState;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.UnassignedBlocks;
import com.blockreality.api.UnassignedReason;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.WorldRevision;
import com.blockreality.impl.BlockRealityMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The drawable half of an analysis, sent to the client.
 *
 * <p>Channel 7 carries immutable beam/shell samples, complete per-element cell lists,
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
 * <p>Element flags are forwarded independently of their f64 DC values. Global maxDc and
 * buckling still use the legacy float/flag compatibility path pending MC64_FORWARD.
 */
public final class StressResultPacket {

    /** Above this many members the rest are dropped — and the drop is logged, never silent. */
    private static final int MAX_MEMBERS = 64;
    /** Facets sent. A floor meshes into one facet per 2x2 block square, so this fills up
     *  far faster than members do — and, like members, the drop is logged. */
    private static final int MAX_SHELLS = 512;


    private final boolean valid;
    private final String invalidReason;

    private final long revision;
    /** Which dimension this result describes; the client drops a mismatch (#41). */
    private final String dimension;
    private final boolean singular;
    private final double maxDc;
    /** Server-side double verdict of {@code maxDc > 1}; see the class javadoc. */
    private final boolean overCapacity;
    private final int islands;
    private final int singularIslands;
    private final double bucklingFactor;
    /** Server-side double verdict of {@code 0 < bucklingFactor <= 1}. */
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
                               List<MemberSnapshot> members, List<ShellSnapshot> shells) {
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
                BucklingState.UNKNOWN, null, 0, 0, 0, Set.of(), Set.of(), List.of(), List.of());
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
        List<MemberSnapshot> m = keepGoverning(r.members(), MAX_MEMBERS,
                "member".equals(r.governingKind()) ? r.governing() : Integer.MIN_VALUE,
                MemberSnapshot::id, "members");
        List<ShellSnapshot> s = keepGoverning(r.shells(), MAX_SHELLS,
                "shell".equals(r.governingKind()) ? r.governing() : Integer.MIN_VALUE,
                ShellSnapshot::id, "plate facets");
        // The one place DISABLED_BY_SCALE can be said. The engine was asked not to run
        // the screen and answered accordingly; only this side knows the reason was size.
        BucklingState state = bucklingSkipped ? BucklingState.DISABLED_BY_SCALE : r.bucklingState();
        int[] byReason = new int[UnassignedReason.values().length];
        for (UnassignedBlocks g : r.unassigned()) {
            byReason[g.reason().ordinal()] += g.blocks().size();
        }
        return new StressResultPacket(true, "",
                r.revision().value(), dimension, r.singular(),
                r.maxDc(), r.maxDc() > 1.0,
                r.islands(), r.singularIslands(),
                r.bucklingFactor(), r.bucklingCritical(), state, byReason,
                r.members().size(), r.shells().size(),
                truncatedBlocks, withheldMembers, withheldShells, m, s);
    }

    /**
     * Truncates to {@code max}, but never drops the governing element: the one number
     * the HUD headlines must correspond to something the player can find drawn, or the
     * overlay says "max D/C 1.31" while every visible element reads safe (#42).
     */
    private static <T> List<T> keepGoverning(List<T> all, int max, int governingId,
                                             java.util.function.ToIntFunction<T> id, String what) {
        if (all.size() <= max) return all;
        BlockRealityMod.LOG.warn(
                "stress overlay truncated: {} {} solved, {} sent — the rest are not drawn",
                all.size(), what, max);
        List<T> kept = new ArrayList<>(all.subList(0, max));
        if (governingId != Integer.MIN_VALUE
                && kept.stream().noneMatch(t -> id.applyAsInt(t) == governingId)) {
            for (T t : all) {
                if (id.applyAsInt(t) == governingId) {
                    kept.set(max - 1, t);
                    break;
                }
            }
        }
        return kept;
    }

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

    /** Smallest linear-buckling load factor; {@code <= 1} means already unstable. */
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
    // Legacy beams still carry the FIELD; shells carry native recovery samples (channel 6). Thirty-odd numbers per member replace eleven
    // stations of four fibres each — about a seventh of the bytes — and the client can
    // then evaluate the exact stress at any point of any block face, which is what a
    // surface contour needs and what interpolating between samples could never give.
    public static void encode(StressResultPacket p, FriendlyByteBuf buf) {
        buf.writeVarLong(p.revision);
        buf.writeUtf(p.dimension, 256);
        buf.writeBoolean(p.singular);
        buf.writeFloat((float) p.maxDc);
        buf.writeBoolean(p.overCapacity);
        buf.writeVarInt(Math.max(0, p.islands));
        buf.writeVarInt(Math.max(0, p.singularIslands));
        buf.writeFloat((float) p.bucklingFactor);
        buf.writeBoolean(p.bucklingCritical);
        buf.writeByte(p.bucklingState.ordinal());
        // Fixed length: both ends come out of the same jar, so the enum cannot differ
        // between them, and a length prefix would only add a number to disagree about.
        for (int c : p.unassignedByReason) buf.writeVarInt(c);
        buf.writeVarInt(p.truncatedBlocks);
        buf.writeVarInt(Math.max(0, p.totalMembers));
        buf.writeVarInt(Math.max(0, p.totalShells));
        buf.writeVarInt(Math.min(p.members.size(), MAX_MEMBERS));

        for (int i = 0; i < p.members.size() && i < MAX_MEMBERS; i++) {
            MemberPacketCodec.write(buf, p.members.get(i), p.withheldMembers.contains(p.members.get(i).id()));
        }

        buf.writeVarInt(Math.min(p.shells.size(), MAX_SHELLS));
        for (int i = 0; i < p.shells.size() && i < MAX_SHELLS; i++) {
            ShellSnapshot s = p.shells.get(i);
            ShellPacketCodec.write(buf, s, p.withheldShells.contains(s.id()));
        }
    }

    /**
     * Truncates rather than throws.
     *
     * <p>{@code writeUtf(s, max)} throws when {@code s} is longer, and this encode runs
     * inside the broadcast loop — one over-long token from an engine that is not the one
     * this build ships would take out the send to every player, not just the drawing of
     * one member. {@code EngineStatusPacket} already guards its two strings this way; the
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
        long revision = buf.readVarLong();
        if (revision < 0) throw new Bad("negative revision");
        String dimension = buf.readUtf(256);
        boolean singular = buf.readBoolean();
        double maxDc = finite(buf.readFloat(), "maxDc");
        boolean overCapacity = buf.readBoolean();
        maxDc = alignToVerdict(maxDc, overCapacity);
        int islands = count(buf.readVarInt(), Integer.MAX_VALUE, "islands");
        int singularIslands = count(buf.readVarInt(), Integer.MAX_VALUE, "singularIslands");
        double bucklingFactor = finite(buf.readFloat(), "bucklingFactor");
        if (bucklingFactor < 0) throw new Bad("negative bucklingFactor");
        boolean bucklingCritical = buf.readBoolean();
        int stateOrdinal = buf.readByte();
        if (stateOrdinal < 0 || stateOrdinal >= BucklingState.values().length) {
            throw new Bad("unknown bucklingState ordinal " + stateOrdinal);
        }
        BucklingState bucklingState = BucklingState.values()[stateOrdinal];
        // A positive factor smaller than the smallest float degrades to 0.0f in transit,
        // which would look like a contradiction while being nothing but the same lossy
        // trip alignToVerdict already handles for maxDc. The server said a factor was
        // computed and that it was positive; the magnitude is what the wire lost, so
        // restore the smallest positive value rather than reject the packet. The
        // verdict itself never depended on this number — it travels as its own flag.
        if (bucklingState.hasFactor() && bucklingFactor == 0) bucklingFactor = Float.MIN_VALUE;
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

        Set<Integer> withheldMembers = new java.util.LinkedHashSet<>();
        Set<Integer> withheldShells = new java.util.LinkedHashSet<>();
        List<MemberSnapshot> members = new ArrayList<>(nMembers);
        for (int i = 0; i < nMembers; i++) {
            MemberPacketCodec.Entry entry = MemberPacketCodec.read(buf);
            members.add(entry.member());
            if (entry.withheld()) withheldMembers.add(entry.member().id());
        }

        int nShells = count(buf.readVarInt(), MAX_SHELLS, "shells");
        List<ShellSnapshot> shells = new ArrayList<>(nShells);
        for (int i = 0; i < nShells; i++) {
            ShellPacketCodec.Entry entry = ShellPacketCodec.read(buf);
            shells.add(entry.shell());
            if (entry.withheld()) withheldShells.add(entry.shell().id());
        }

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
                truncatedBlocks, withheldMembers, withheldShells, members, shells);
    }

    /**
     * Nudges a float-degraded D/C onto the side of 1.0 the server ruled for, so every
     * downstream comparison ({@code isOverloaded}, palette thresholds) agrees with the
     * carried verdict. The shift is at most one ulp around 1.0 — far inside the display
     * track's 1e-5 budget — and only fires when the rounding actually crossed the line.
     */
    private static double alignToVerdict(double dc, boolean overloaded) {
        if (overloaded && dc <= 1.0) return Math.nextUp(1.0);
        if (!overloaded && dc > 1.0) return 1.0;
        return dc;
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
    private static double finite(float f, String what) {
        if (!Float.isFinite(f)) throw new Bad(what + " is not finite");
        return f;
    }

    // ---------------------------------------------------------------- handle
    /**
     * The client-only type is named by its fully qualified name inside the supplier, and
     * is deliberately <em>not</em> imported. An import would put it in this class's
     * constant pool, and a dedicated server verifying this class would then try to
     * resolve a class that does not exist on its side.
     */
    public static void handle(StressResultPacket p, Supplier<NetworkEvent.Context> ctx) {
        if (!p.valid) {
            BlockRealityMod.LOG.warn("dropping malformed stress packet: {}", p.invalidReason);
        } else {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.blockreality.impl.client.ClientStressState.accept(p)));
        }
        ctx.get().setPacketHandled(true);
    }

    public WorldRevision worldRevision() { return new WorldRevision(revision); }
}
