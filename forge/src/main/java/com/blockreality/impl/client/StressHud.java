package com.blockreality.impl.client;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.ScanMode;
import com.blockreality.api.StressStation;
import com.blockreality.api.render.Rgb;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.render.SectionDiagram;
import com.blockreality.impl.BRContent;
import com.blockreality.impl.BlockRealityMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The readable half of the instrument.
 *
 * <p>A 3D overlay can show that the top and bottom of a member differ. It cannot say which
 * is which, by how much, or where the sign changes — and it certainly cannot say it in
 * words. So the answer lives here, as the section diagram every structures textbook uses:
 * a linear stress profile across the depth, the neutral axis marked, and both faces
 * <strong>labelled</strong>.
 *
 * <p>The labels are the point. Whether the top of a beam is in tension is not a property of
 * beams: a cantilever hogs and puts its top in tension; the same beam sitting on two
 * supports sags and puts its top in compression. Both are right. Colour alone leaves the
 * reader to decide which case they are in, and a reader who has only ever seen the
 * cantilever will conclude the tool is inverted.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, value = Dist.CLIENT)
public final class StressHud {

    private StressHud() { }

    private static final int PAD = 6;
    private static final int SWATCH = 8;

    /** Section diagram box, pixels. */
    private static final int DIA_W = 96;
    private static final int DIA_H = 56;

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!mc.player.getMainHandItem().is(BRContent.STRESS_GLASSES.get())
                && !mc.player.getOffhandItem().is(BRContent.STRESS_GLASSES.get())) return;

        GuiGraphics g = event.getGuiGraphics();
        int x = PAD;
        int y = PAD;

        ScanMode mode = ClientStressState.mode();
        g.drawString(mc.font, Component.translatable("br.hud.mode",
                Component.translatable("br.scan.mode." + mode.name().toLowerCase(Locale.ROOT))),
                x, y, 0xFFFFFF);
        y += 12;

        if (!ClientStressState.engineStatus().isEmpty()) {
            g.drawString(mc.font, Component.translatable("br.hud.engine_unavailable",
                    ClientStressState.engineDetail()), x, y, 0xFF6B6B);
            return;
        }
        // Mechanism BEFORE the no-data return: both states have empty member lists, so
        // checking hasData first made this branch unreachable and told the player who
        // built an unrestrained structure "No analysis yet" — the opposite of the one
        // thing the analysis had actually concluded (#43).
        if (ClientStressState.mechanism()) {
            // Not a failure and not a safe structure: nothing is holding it up, so there
            // are no stresses. Reporting "0 MPa" here would be a lie.
            g.drawString(mc.font, Component.translatable("br.hud.mechanism"), x, y, 0xFFCC00);
            return;
        }
        if (!ClientStressState.hasData()) {
            g.drawString(mc.font, Component.translatable("br.hud.no_data"), x, y, 0xAAAAAA);
            return;
        }
        // Stale is a label, not a blank: the numbers are real, for a world that has
        // since changed. Saying so is the display track's half of invariant 5 (INV-4).
        if (ClientStressState.stale()) {
            g.drawString(mc.font, Component.translatable("br.hud.stale"), x, y, 0xC8860D);
            y += 12;
        }
        if (ClientStressState.truncated()) {
            // The picture is incomplete; the governing element is guaranteed drawn,
            // but "what you see" is not "all there is" and the HUD must say so (#42).
            g.drawString(mc.font, Component.translatable("br.hud.truncated",
                    ClientStressState.members().size(), ClientStressState.totalMembers(),
                    ClientStressState.shells().size(), ClientStressState.totalShells()),
                    x, y, 0xC8860D);
            y += 12;
        }
        if (ClientStressState.partialMechanism()) {
            // Some structure in the world is unrestrained and others are not. Returning
            // here — as this did before the engine started solving each building
            // separately — blanked the overlay for every sound building in sight.
            g.drawString(mc.font, Component.translatable("br.hud.island_mechanism",
                    ClientStressState.singularIslands(), ClientStressState.islands()),
                    x, y, 0xFFCC00);
            y += 12;
        }

        // Red is the SERVER's over-capacity verdict, not a float comparison here (#55).
        g.drawString(mc.font, Component.translatable("br.hud.maxdc",
                String.format(Locale.ROOT, "%.3f", ClientStressState.maxDc())), x, y,
                ClientStressState.overCapacity() ? 0xFF6B6B : 0xFFFFFF);
        y += 12;
        g.drawString(mc.font, Component.translatable("br.hud.members",
                ClientStressState.members().size()), x, y, 0xAAAAAA);
        y += 11;
        if (!ClientStressState.shells().isEmpty()) {
            g.drawString(mc.font, Component.translatable("br.hud.plates",
                    ClientStressState.shells().size()), x, y, 0xAAAAAA);
            y += 11;
        }
        // Stability is a SEPARATE answer from strength and is printed as one. A slender
        // column reaches its buckling load at a stress the D/C line calls comfortable, so a
        // player who only ever sees D/C is being told the safe half of the story.
        if (ClientStressState.bucklingFactor() > 0) {
            boolean crit = ClientStressState.bucklingCritical();
            g.drawString(mc.font, Component.translatable(
                    crit ? "br.hud.buckling_critical" : "br.hud.buckling",
                    String.format(Locale.ROOT, "%.2f", ClientStressState.bucklingFactor())),
                    x, y, crit ? 0xFF6B6B : 0xAAAAAA);
            y += 11;
        } else if (ClientStressState.bucklingSkipped()) {
            // Skipped is a fact worth a line: a blank here reads as "stable", which is a
            // different and unearned claim (the 0-conflation is on record, V04_PLAN §2.6).
            g.drawString(mc.font, Component.translatable("br.hud.buckling_skipped"),
                    x, y, 0xC8A24A);
            y += 11;
        }
        // Part of what the server tracks was in a chunk it could not read, and the pieces
        // standing against that boundary were left uncoloured. Saying so is the whole
        // point of #74: the previous behaviour was a confident number about a structure
        // the engine had only seen part of.
        if (ClientStressState.truncatedBlocks() > 0) {
            g.drawString(mc.font, Component.translatable("br.hud.model_incomplete",
                    ClientStressState.truncatedBlocks()), x, y, 0xC8A24A);
            y += 11;
        }
        // Without this the contour is only ordinal. With it, a colour can be read as MPa.
        g.drawString(mc.font, Component.translatable("br.hud.scale",
                String.format(Locale.ROOT, "%.2f", ClientStressState.colourScaleMpa())),
                x, y, 0xAAAAAA);
        y += 14;

        Optional<MemberSnapshot> focus = ClientStressState.focusedMember();
        if (focus.isPresent()) {
            y = drawFocus(g, mc, x, y, focus.get());
            return;
        }

        Optional<ShellSnapshot> plate = ClientStressState.focusedShell();
        if (plate.isPresent()) {
            y = drawPlateFocus(g, mc, x, y, plate.get());
            return;
        }

        g.drawString(mc.font, Component.translatable("br.hud.aim"), x, y, 0xAAAAAA);
        y += 14;
        drawLegend(g, mc, x, y, mode);
    }

    /**
     * Everything about the one plate facet being looked at.
     *
     * <p>Deliberately not the member readout with different words. A plate has no fibres
     * and no neutral <em>axis</em>: it has two faces carrying equal and opposite bending,
     * and a demand governed by a von Mises value that is a combination of three stress
     * components rather than a single one. Printing "governing fibre" over a plate would
     * be borrowing a beam's vocabulary for something that does not have one.
     */
    private static int drawPlateFocus(GuiGraphics g, Minecraft mc, int x, int y, ShellSnapshot s) {
        g.drawString(mc.font, Component.translatable("br.hud.focus_plate",
                s.id(), s.plate(), String.format(Locale.ROOT, "%.0f", s.thicknessMm())),
                x, y, 0x9FE8FF);
        y += 11;

        g.drawString(mc.font, Component.translatable("br.hud.plate_dc",
                String.format(Locale.ROOT, "%.3f", s.dc())),
                x, y, s.dc() > 1.0 ? 0xFF6B6B : 0xFFFFFF);
        y += 11;

        if (s.field().isPresent()) {
            var f = s.field().get();
            g.drawString(mc.font, Component.translatable("br.hud.plate_faces",
                    String.format(Locale.ROOT, "%+.2f", f.signedPrincipal(0, 0, +1)),
                    String.format(Locale.ROOT, "%+.2f", f.signedPrincipal(0, 0, -1))),
                    x, y, 0xFFFFFF);
            y += 11;
        }

        // Said out loud, because the recovered number is the one the demand is based on
        // and it is NOT what the element reported at its corner. A reader who compares the
        // two should be told which they are looking at.
        if (s.edgeRecovered()) {
            g.drawString(mc.font, Component.translatable("br.hud.plate_recovered"), x, y, 0xC8860D);
            y += 11;
        }
        return y;
    }

    /** Everything about the one member being looked at. */
    private static int drawFocus(GuiGraphics g, Minecraft mc, int x, int y, MemberSnapshot m) {
        g.drawString(mc.font, Component.translatable("br.hud.focus", m.id(), m.section()),
                x, y, 0x9FE8FF);
        y += 11;

        if (ClientStressState.withheld(m)) {
            // The number exists, and it is about a structure with a piece missing. Showing
            // it greyed would still be showing it, and a player reads a greyed number as a
            // number. So the reason goes here instead of the ratio (N14-c).
            g.drawString(mc.font, Component.translatable("br.hud.focus_withheld"),
                    x, y, 0xC8A24A);
            y += 11;
        } else {
            g.drawString(mc.font, Component.translatable("br.hud.focus_dc",
                    String.format(Locale.ROOT, "%.3f", m.dc()),
                    Component.translatable("br.fibre." + m.governingFibre().name().toLowerCase(Locale.ROOT))),
                    x, y, m.isOverloaded() ? 0xFF6B6B : 0xFFFFFF);
            y += 11;
        }

        Optional<StressStation> st = ClientStressState.focusedStation();
        Optional<SectionDiagram> sd = ClientStressState.focusedSection();
        if (st.isEmpty() || sd.isEmpty()) return y;

        g.drawString(mc.font, Component.translatable("br.hud.at_x",
                String.format(Locale.ROOT, "%.2f", st.get().xMm() / 1000.0)), x, y, 0xAAAAAA);
        y += 13;

        return drawSectionDiagram(g, mc, x, y, sd.get());
    }

    /**
     * The section, drawn as a stress profile.
     *
     * <p>A vertical line is the section's depth and the zero-stress axis. Each row is a
     * horizontal bar whose length is the stress at that depth — right of the axis for
     * tension, left for compression — so the shape is the familiar triangle for pure
     * bending and a trapezoid once axial force is added. Where the bar crosses the axis is
     * the neutral axis, and it is drawn where it actually is rather than assumed to be at
     * the centroid.
     */
    private static int drawSectionDiagram(GuiGraphics g, Minecraft mc, int x, int y, SectionDiagram d) {
        final int axisX = x + DIA_W / 2;
        final int top = y;
        final int bottom = y + DIA_H;

        double peak = d.peakMagnitudeMpa();
        if (peak <= 0) peak = 1;

        StressPalette p = ClientStressState.palette();

        // Section outline: a plain box so the depth is visible even where stress is zero.
        g.fill(x, top, x + DIA_W, top + 1, 0x66FFFFFF);
        g.fill(x, bottom, x + DIA_W, bottom + 1, 0x66FFFFFF);
        g.fill(axisX, top, axisX + 1, bottom, 0x88FFFFFF);

        for (int row = 0; row < DIA_H; row++) {
            double fraction = (double) row / (DIA_H - 1);
            double sigma = d.sigmaAt(fraction);
            int len = (int) Math.round(Math.abs(sigma) / peak * (DIA_W / 2.0 - 2));
            if (len <= 0) continue;

            Rgb c = p.signedStress(sigma, peak);
            int argb = c.argb(0.85f);
            int ry = top + row;
            if (sigma > 0) g.fill(axisX + 1, ry, axisX + 1 + len, ry + 1, argb);
            else g.fill(axisX - len, ry, axisX, ry + 1, argb);
        }

        // Neutral axis, only when the section really has one.
        d.neutralFraction().ifPresent(f -> {
            int ny = top + (int) Math.round(f * (DIA_H - 1));
            g.fill(x, ny, x + DIA_W, ny + 1, 0xCCFFFFFF);
        });

        // The words. Without these the picture is still ambiguous.
        int labelX = x + DIA_W + 6;
        g.drawString(mc.font, Component.translatable("br.section.top",
                        Component.translatable(d.topLabelKey()),
                        String.format(Locale.ROOT, "%+.2f", d.topSigmaMpa())),
                labelX, top - 2, tint(d.topSigmaMpa(), p));
        g.drawString(mc.font, Component.translatable("br.section.bottom",
                        Component.translatable(d.bottomLabelKey()),
                        String.format(Locale.ROOT, "%+.2f", d.bottomSigmaMpa())),
                labelX, bottom - 7, tint(d.bottomSigmaMpa(), p));
        if (d.neutralFraction().isPresent()) {
            g.drawString(mc.font, Component.translatable("br.section.neutral"),
                    labelX, top + DIA_H / 2 - 4, 0xCCCCCC);
        }

        return bottom + 12;
    }

    private static int tint(double sigma, StressPalette p) {
        if (Math.abs(sigma) < 1e-9) return p.zeroColour().argb(1f);
        return (sigma > 0 ? p.tensionColour() : p.compressionColour()).argb(1f);
    }

    /**
     * The legend for the lens actually being used.
     *
     * <p>It printed the utilisation legend in every mode, which made the material lens
     * look like a broken utilisation lens rather than a different question — and the
     * material lens was itself drawing stress at the time, so the two wrongs agreed.
     */
    private static void drawLegend(GuiGraphics g, Minecraft mc, int x, int y, ScanMode mode) {
        List<StressPalette.LegendStop> stops = switch (mode) {
            case MATERIAL -> StressPalette.materialLegend();
            case STRESS -> ClientStressState.palette().stressLegend();
            default -> StressPalette.utilizationLegend();
        };
        for (StressPalette.LegendStop stop : stops) {
            g.fill(x, y + 1, x + SWATCH, y + 1 + SWATCH, stop.colour().argb(1f));
            g.drawString(mc.font, Component.translatable(stop.translationKey()),
                    x + SWATCH + 4, y, 0xFFFFFF);
            y += 11;
        }
    }
}
