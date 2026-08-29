package com.blockreality.impl.client;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.StressStation;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.api.ScanMode;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.render.MemberPick;
import com.blockreality.core.render.ShellMesh;
import com.blockreality.core.render.SectionDiagram;
import com.blockreality.core.render.StressRibbon;
import com.blockreality.core.render.StressRibbonBuilder;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.net.AnalysisPendingPacket;
import com.blockreality.core.sidecar.SidecarClient;
import com.blockreality.impl.net.EngineStatusPacket;
import com.blockreality.impl.net.StressResultPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Optional;

/**
 * What the client currently knows about stress. Client-only state, main thread only.
 *
 * <p>The ribbons are rebuilt when a packet arrives, not per frame. A frame draws a
 * prepared vertex list and performs no engineering at all.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientStressState {

    private ClientStressState() { }

    // UTILIZATION first: at building scale the useful question is "which member is in
    // trouble", and that is one colour per member. The section detail is a second look,
    // at one member, once you know where to look.
    private static ScanMode mode = ScanMode.UTILIZATION;
    private static StressPalette palette = StressPalette.SIGNED_DEFAULT;

    private static long revision = -1;
    /** The dimension the data belongs to; drawn only while the player is in it (#41). */
    private static String dimension = "";
    private static boolean singular;
    private static double maxDc;
    /** Server-side double verdicts; the client displays them, never re-derives (#55). */
    private static boolean overCapacity;
    private static boolean bucklingCriticalFlag;
    private static boolean bucklingSkippedFlag;
    /** Tracked blocks the server could not read that touched this request (#74, N14). */
    private static int truncatedBlocks;
    /** Ids whose verdict the server withheld because their input was cut (N14-c). */
    private static java.util.Set<Integer> withheldMembers = java.util.Set.of();
    private static java.util.Set<Integer> withheldShells = java.util.Set.of();
    private static List<MemberSnapshot> members = List.of();
    private static List<ShellSnapshot> shells = List.of();
    private static List<BlockKey> plateBlocks = List.of();
    private static int islands;
    private static int singularIslands;
    private static double bucklingFactor;
    private static int totalMembers;
    private static int totalShells;
    private static List<StressRibbon> ribbons = List.of();
    private static String engineStatus = "";
    private static String engineDetail = "";
    /** Newest world revision the server has announced; above {@link #revision} = stale. */
    private static long pendingRevision = -1;

    public static ScanMode mode() { return mode; }

    public static StressPalette palette() { return palette; }

    public static List<StressRibbon> ribbons() { return ribbons; }

    public static List<MemberSnapshot> members() { return members; }

    public static List<ShellSnapshot> shells() { return shells; }

    /** Every block that belongs to a facet, deduplicated — a block can be in four. */
    public static List<BlockKey> plateBlocks() { return plateBlocks; }

    public static int islands() { return islands; }

    public static int singularIslands() { return singularIslands; }

    public static double bucklingFactor() { return bucklingFactor; }

    /**
     * Some structure is at or past its linear buckling load — the SERVER's verdict,
     * carried by the packet. Comparing the float32-degraded factor against 1.0 here
     * could flip the judgement within a ulp of the boundary (#55).
     */
    public static boolean bucklingCritical() { return bucklingCriticalFlag; }

    /** The server skipped the buckling screen for size; the HUD must say so. */
    public static boolean bucklingSkipped() { return bucklingSkippedFlag; }

    public static int truncatedBlocks() { return truncatedBlocks; }

    /** True when this member's structure ran into a chunk nobody could read (N14-c). */
    public static boolean withheld(MemberSnapshot m) { return withheldMembers.contains(m.id()); }

    public static boolean withheldShell(ShellSnapshot s) { return withheldShells.contains(s.id()); }

    /** Whether max D/C exceeds 1 — the server's double-precision verdict (#55). */
    public static boolean overCapacity() { return overCapacity; }

    public static long revision() { return revision; }

    public static boolean singular() { return singular; }

    public static double maxDc() { return maxDc; }

    public static String engineStatus() { return engineStatus; }

    public static String engineDetail() { return engineDetail; }

    public static boolean hasData() { return revision >= 0 && (!members.isEmpty() || !shells.isEmpty()); }

    /**
     * The world was solved and NOTHING is restrained: a mechanism verdict, which has a
     * revision but no members or shells. Distinct from "no analysis yet" — the check
     * must run before {@link #hasData}, which is false for both (#43).
     */
    public static boolean mechanism() { return revision >= 0 && singular && members.isEmpty() && shells.isEmpty(); }

    /** The world has moved past what is on screen; the HUD labels it stale (INV-4). */
    public static boolean stale() { return hasData() && pendingRevision > revision; }

    /** Solved totals before the packet cap; when larger than the lists, the HUD says so. */
    public static int totalMembers() { return totalMembers; }

    public static int totalShells() { return totalShells; }

    public static boolean truncated() {
        return totalMembers > members.size() || totalShells > shells.size();
    }

    // ------------------------------------------------------------------- focus
    private static int focusedMemberId = -1;

    public static int focusedMemberId() { return focusedMemberId; }

    public static Optional<MemberSnapshot> focusedMember() {
        for (MemberSnapshot m : members) if (m.id() == focusedMemberId) return Optional.of(m);
        return Optional.empty();
    }

    /**
     * The station of the focused member that governs its D/C — the section worth showing.
     * Falls back to the first station when the engine reported no governing index.
     */
    public static Optional<StressStation> focusedStation() {
        return focusedMember().flatMap(m -> {
            List<StressStation> st = m.stations();
            if (st.isEmpty()) return Optional.empty();
            int i = m.governingStation();
            return Optional.of(st.get(i >= 0 && i < st.size() ? i : 0));
        });
    }

    /**
     * The facet the camera is on, when it is not on a member.
     *
     * <p>Members win when both are hit. A beam buried in a floor is the more specific
     * answer, and it is also the smaller target — losing it to the slab behind it would
     * make it unselectable.
     */
    public static Optional<ShellSnapshot> focusedShell() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || shells.isEmpty() || focusedMemberId >= 0) return Optional.empty();
        var hit = mc.hitResult;
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult bhr)) return Optional.empty();
        BlockKey k = new BlockKey(bhr.getBlockPos().getX(), bhr.getBlockPos().getY(), bhr.getBlockPos().getZ());
        for (ShellSnapshot s : shells) if (s.blocks().contains(k)) return Optional.of(s);
        return Optional.empty();
    }

    public static Optional<SectionDiagram> focusedSection() {
        return focusedStation().flatMap(SectionDiagram::of);
    }

    /** Recomputed on the client tick from where the camera is looking. */
    public static void updateFocus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || members.isEmpty()) { focusedMemberId = -1; return; }
        var eye = mc.player.getEyePosition();
        var look = mc.player.getLookAngle();
        focusedMemberId = MemberPick.pick(
                new Vec3d(eye.x, eye.y, eye.z),
                new Vec3d(look.x, look.y, look.z),
                members).orElse(-1);
    }

    public static void accept(StressResultPacket p) {
        // The handler already dropped invalid packets; this is defence in depth for
        // any future caller that skips it.
        if (!p.valid()) return;
        // A result for another dimension is not data about the world on screen. The
        // events in ClientEvents clear the state on travel; this guard covers the
        // packet that was already in flight when the player left (#41).
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null
                && !p.dimension().equals(mc.level.dimension().location().toString())) {
            BlockRealityMod.LOG.debug("dropping stress result for {} while in {}",
                    p.dimension(), mc.level.dimension().location());
            return;
        }
        revision = p.revision();
        dimension = p.dimension();
        singular = p.singular();
        maxDc = p.maxDc();
        overCapacity = p.overCapacity();
        islands = p.islands();
        singularIslands = p.singularIslands();
        bucklingFactor = p.bucklingFactor();
        bucklingCriticalFlag = p.bucklingCritical();
        bucklingSkippedFlag = p.bucklingSkipped();
        truncatedBlocks = p.truncatedBlocks();
        withheldMembers = p.withheldMembers();
        withheldShells = p.withheldShells();
        totalMembers = p.totalMembers();
        totalShells = p.totalShells();
        members = p.members();
        shells = p.shells();
        engineStatus = "";
        if (pendingRevision < revision) pendingRevision = revision;
        rebuild();
    }

    /** The server says the world moved on; what is on screen becomes stale (INV-4). */
    public static void acceptPending(AnalysisPendingPacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null
                && !p.dimension().equals(mc.level.dimension().location().toString())) {
            return;
        }
        if (p.revision() > pendingRevision) pendingRevision = p.revision();
    }

    /**
     * Forgets everything. Called on logout, respawn and dimension change: stress state
     * is keyed to one world's coordinates, and carrying it across a travel paints the
     * old world's overlay onto whatever now occupies those positions (#41).
     */
    public static void clear() {
        revision = -1;
        dimension = "";
        singular = false;
        maxDc = 0;
        overCapacity = false;
        bucklingCriticalFlag = false;
        bucklingSkippedFlag = false;
        truncatedBlocks = 0;
        withheldMembers = java.util.Set.of();
        withheldShells = java.util.Set.of();
        islands = 0;
        singularIslands = 0;
        bucklingFactor = 0;
        totalMembers = 0;
        totalShells = 0;
        members = List.of();
        shells = List.of();
        plateBlocks = List.of();
        ribbons = List.of();
        occupied = java.util.Set.of();
        colourScaleMpa = 1;
        engineStatus = "";
        engineDetail = "";
        pendingRevision = -1;
        focusedMemberId = -1;
    }

    /**
     * True when some structure in the world is a mechanism but others are not.
     *
     * <p>The HUD has to say this differently from "nothing is holding anything up": with
     * ten buildings and one unsupported shed, nine sets of numbers on screen are real.
     */
    public static boolean partialMechanism() { return singular && hasData(); }

    public static void acceptStatus(EngineStatusPacket p) {
        engineStatus = p.status();
        engineDetail = p.detail();
        // The old ribbons are kept either way. Clearing them would look identical to
        // "this structure is unstressed", which is a different and much more reassuring
        // claim than the truth.
        //
        // Two different things arrive on this packet and they were saying the same
        // sentence. A HEALTHY engine that refused THIS MODEL — a load on a block that
        // forms no element, an unknown token — is not an unavailable engine, and telling
        // the player it is sends them to look at their install while /br status prints a
        // green READY next to it (PR26_REVIEW DF-04). The status field already
        // distinguishes them; only the message did not.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            if (p.detail().startsWith(EngineStatusPacket.PLATFORM_PREFIX)) {
                // The one absence the player cannot fix: no bundled engine for this
                // platform. A sentence in their language, not a relayed log line.
                mc.player.displayClientMessage(Component.translatable("br.engine.platform",
                        p.detail().substring(EngineStatusPacket.PLATFORM_PREFIX.length())), true);
            } else {
                String key = SidecarClient.Status.READY.name().equals(p.status())
                        ? "br.engine.refused" : "br.engine.unavailable";
                mc.player.displayClientMessage(Component.translatable(key, p.detail()), true);
            }
        }
    }

    public static void cycleMode() {
        mode = mode.nextDemoMode();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("br.scan.mode." + mode.name().toLowerCase(java.util.Locale.ROOT)), true);
        }
    }

    public static void cyclePalette() {
        palette = palette == StressPalette.SIGNED_DEFAULT
                ? StressPalette.TEACHING : StressPalette.SIGNED_DEFAULT;
        rebuild();
    }

    // Occupied cells and the colour scale are computed once per packet, not per frame:
    // a frame draws tens of thousands of vertices and must not also be rebuilding sets.
    private static java.util.Set<Long> occupied = java.util.Set.of();
    private static double colourScaleMpa = 1;

    public static java.util.Set<Long> occupiedCells() { return occupied; }

    /**
     * The stress that maps to full saturation, shared by every member so the contour is
     * comparable across the structure — which is the whole point of a contour plot. It is
     * also the number the legend prints, so the colours can be read back as MPa.
     */
    public static double colourScaleMpa() { return colourScaleMpa; }

    private static void rebuild() {
        java.util.LinkedHashSet<BlockKey> plate = new java.util.LinkedHashSet<>();
        for (ShellSnapshot s : shells) plate.addAll(s.blocks());
        plateBlocks = List.copyOf(plate);

        if (members.isEmpty() && shells.isEmpty()) {
            ribbons = List.of();
            occupied = java.util.Set.of();
            colourScaleMpa = 1;
            return;
        }
        // Each member is scaled to its OWN peak, which is the opposite of what a shared
        // scale would do — and now correct, because the two questions have been split.
        // Cross-member comparison is carried by the utilisation colour on the axis line;
        // the fibre ribbons only ever show one member at a time, so their job is to make
        // that member's internal distribution as legible as possible.
        // Elements whose input was cut are drawn as plain blocks: no contour, no
        // utilisation colour (N14-c). Colour here means "this is how close to failing it
        // is", and that sentence is not available for a structure the engine only saw
        // part of. They stay in `members` so the picker can still name them and say why.
        List<MemberSnapshot> shown = members.stream().filter(m -> !withheld(m)).toList();
        List<ShellSnapshot> shownShells = shells.stream().filter(s -> !withheldShell(s)).toList();
        occupied = StressSurfaceRenderer.cellsOf(shown, shownShells);

        // One scale for beams AND plates. That is only defensible because both report the
        // same quantity: a beam's fibre stress and a plate's largest signed principal
        // stress are both tension-positive MPa on the material's surface. Two scales would
        // make a lightly loaded floor look exactly as alarming as an overstressed beam.
        double peak = 0;
        for (MemberSnapshot m : shown) {
            if (m.field().isPresent()) peak = Math.max(peak, m.field().get().peakMagnitudeMpa(21));
        }
        peak = Math.max(peak, ShellMesh.peakMpa(shownShells));
        colourScaleMpa = peak > 0 ? peak : 1;

        List<StressRibbon> out = new java.util.ArrayList<>(shown.size());
        for (MemberSnapshot m : shown) {
            out.add(StressRibbonBuilder.build(m, palette, StressRibbonBuilder.memberPeak(m)));
        }
        ribbons = List.copyOf(out);
    }
}
