package com.blockreality.impl.client;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ScanMode;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.render.StressRibbon;
import com.blockreality.core.render.StressRibbonBuilder;
import com.blockreality.impl.net.EngineStatusPacket;
import com.blockreality.impl.net.StressResultPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * What the client currently knows about stress. Client-only state, main thread only.
 *
 * <p>The ribbons are rebuilt when a packet arrives, not per frame. A frame draws a
 * prepared vertex list and performs no engineering at all.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientStressState {

    private ClientStressState() { }

    private static ScanMode mode = ScanMode.STRESS;
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

    private static void rebuild() {
        if (members.isEmpty()) {
            ribbons = List.of();
            return;
        }
        // One scale across the whole structure, so members are comparable to each other.
        // Per-member scaling would make a barely loaded beam look as vivid as a failing
        // one, which is the more useful view of a single member and the wrong view of a
        // building.
        double peak = 0;
        for (MemberSnapshot m : members) peak = Math.max(peak, m.peakMagnitudeMpa());
        if (peak <= 0) peak = 1;

        List<StressRibbon> out = new java.util.ArrayList<>(members.size());
        for (MemberSnapshot m : members) out.add(StressRibbonBuilder.build(m, palette, peak));
        ribbons = List.copyOf(out);
    }
}
