package com.blockreality.impl.client;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.StressStation;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.api.ScanMode;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.render.MemberPick;
import com.blockreality.core.render.SectionDiagram;
import com.blockreality.core.render.StressRibbon;
import com.blockreality.core.render.StressRibbonBuilder;
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
    private static boolean singular;
    private static double maxDc;
    private static List<MemberSnapshot> members = List.of();
    private static List<StressRibbon> ribbons = List.of();
    private static String engineStatus = "";
    private static String engineDetail = "";

    public static ScanMode mode() { return mode; }

    public static StressPalette palette() { return palette; }

    public static List<StressRibbon> ribbons() { return ribbons; }

    public static List<MemberSnapshot> members() { return members; }

    public static long revision() { return revision; }

    public static boolean singular() { return singular; }

    public static double maxDc() { return maxDc; }

    public static String engineStatus() { return engineStatus; }

    public static String engineDetail() { return engineDetail; }

    public static boolean hasData() { return revision >= 0 && !members.isEmpty(); }

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
        revision = p.revision();
        singular = p.singular();
        maxDc = p.maxDc();
        members = p.members();
        engineStatus = "";
        rebuild();
    }

    public static void acceptStatus(EngineStatusPacket p) {
        engineStatus = p.status();
        engineDetail = p.detail();
        // The old ribbons are kept and the HUD says the engine is unavailable. Clearing
        // them would look identical to "this structure is unstressed", which is a
        // different and much more reassuring claim than the truth.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("br.engine.unavailable", p.detail()), true);
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
        if (members.isEmpty()) {
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
        occupied = StressSurfaceRenderer.cellsOf(members);

        double peak = 0;
        for (MemberSnapshot m : members) {
            if (m.field().isPresent()) peak = Math.max(peak, m.field().get().peakMagnitudeMpa(21));
        }
        colourScaleMpa = peak > 0 ? peak : 1;

        List<StressRibbon> out = new java.util.ArrayList<>(members.size());
        for (MemberSnapshot m : members) {
            out.add(StressRibbonBuilder.build(m, palette, StressRibbonBuilder.memberPeak(m)));
        }
        ribbons = List.copyOf(out);
    }
}
