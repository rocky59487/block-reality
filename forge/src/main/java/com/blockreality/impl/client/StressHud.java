package com.blockreality.impl.client;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.UnassignedReason;
import com.blockreality.api.ScanMode;
import com.blockreality.api.StressStation;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.render.SectionDiagram;
import com.blockreality.impl.BRContent;
import com.blockreality.impl.BlockRealityMod;
import net.minecraft.client.Minecraft;
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

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!mc.player.getMainHandItem().is(BRContent.STRESS_GLASSES.get())
                && !mc.player.getOffhandItem().is(BRContent.STRESS_GLASSES.get())) return;

        HudPanel panel = new HudPanel(mc);
        populate(panel);
        panel.render(event.getGuiGraphics());
    }

    private static void populate(HudPanel panel) {
        ScanMode mode = ClientStressState.mode();
        panel.line(Component.translatable("br.hud.mode",
                Component.translatable("br.scan.mode." + mode.name().toLowerCase(Locale.ROOT)))
                .withStyle(net.minecraft.ChatFormatting.BOLD), HudPanel.PRIMARY);
        panel.gap();

        var notice = ClientStressState.notice();
        if (notice != null && (notice != com.blockreality.impl.net.AnalysisUpdatePacket.Kind.PENDING
                || !ClientStressState.hasData())) {
            String key = switch (notice) {
                case EMPTY -> "br.hud.empty";
                case OFF -> "br.hud.off";
                case PENDING -> ClientStressState.engineDetail().isEmpty() ? "br.hud.pending" : "br.hud.input_waiting";
                case MODEL_REFUSED -> "br.hud.model_refused";
                default -> "br.hud.engine_unavailable";
            };
            int colour = switch (notice) {
                case ENGINE_UNAVAILABLE -> HudPanel.CRITICAL;
                case MODEL_REFUSED -> HudPanel.WARNING;
                default -> HudPanel.DETAIL;
            };
            panel.line(Component.translatable(key, ClientStressState.engineDetail()), colour);
            return;
        }
        if (notice == com.blockreality.impl.net.AnalysisUpdatePacket.Kind.PENDING
                && !ClientStressState.engineDetail().isEmpty()) {
            panel.line(Component.translatable("br.hud.input_waiting"), HudPanel.WARNING);
        }
        // Stale is a label, not a blank: the numbers are real, for a world that has
        // since changed. Saying so is the display track's half of invariant 5 (INV-4).
        if (ClientStressState.stale()) {
            panel.line(Component.translatable("br.hud.stale"), HudPanel.WARNING);
        }
        // Mechanism BEFORE the no-data return: both states have empty member lists, so
        // checking hasData first made this branch unreachable and told the player who
        // built an unrestrained structure "No analysis yet" — the opposite of the one
        // thing the analysis had actually concluded (#43).
        if (ClientStressState.mechanism()) {
            // Not a failure and not a safe structure: nothing is holding it up, so there
            // are no stresses. Reporting "0 MPa" here would be a lie.
            panel.line(Component.translatable("br.hud.mechanism"), HudPanel.MECHANISM);
            return;
        }
        if (!ClientStressState.hasData()) {
            panel.line(Component.translatable("br.hud.no_data"), HudPanel.DETAIL);
            return;
        }
        if (ClientStressState.totalMembers() == 0 && ClientStressState.totalShells() == 0) {
            panel.line(Component.translatable("br.hud.no_elements"), HudPanel.WARNING);
            for (UnassignedReason reason : UnassignedReason.values()) {
                int count = ClientStressState.unassignedCount(reason);
                if (count > 0) {
                    panel.line(Component.translatable(reason.translationKey(), count), HudPanel.DETAIL);
                }
            }
            return;
        }
        if (ClientStressState.truncated()) {
            // Whole elements can be omitted by the delivery budget; counts remain explicit.
            panel.line(Component.translatable("br.hud.truncated",
                    ClientStressState.members().size(), ClientStressState.totalMembers(),
                    ClientStressState.shells().size(), ClientStressState.totalShells()), HudPanel.WARNING);
        }
        if (ClientStressState.governingOmitted()) {
            panel.line(Component.translatable("br.hud.governing_omitted"), HudPanel.WARNING);
        }
        if (ClientStressState.partialMechanism()) {
            // Some structure in the world is unrestrained and others are not. Returning
            // here — as this did before the engine started solving each building
            // separately — blanked the overlay for every sound building in sight.
            panel.line(Component.translatable("br.hud.island_mechanism",
                    ClientStressState.singularIslands(), ClientStressState.islands()), HudPanel.MECHANISM);
        }

        // Stability is a SEPARATE answer from strength and is printed as one. A slender
        // column reaches its buckling load at a stress the D/C line calls comfortable, so a
        // player who only ever sees D/C is being told the safe half of the story.
        for (var row : ClientStressState.bucklingReadout()) {
            int colour = switch (row.tone()) {
                case CRITICAL -> HudPanel.CRITICAL;
                case UNEVALUATED -> HudPanel.WARNING;
                case DETAIL -> HudPanel.DETAIL;
            };
            panel.line(Component.translatableWithFallback(row.key(), row.fallback(),
                    row.arguments().toArray()), colour);
        }
        panel.gap();
        // Red is the SERVER's over-capacity verdict, not a float comparison here (#55).
        panel.line(Component.translatable("br.hud.maxdc",
                String.format(Locale.ROOT, "%.3f", ClientStressState.maxDc())).withStyle(net.minecraft.ChatFormatting.BOLD), ClientStressState.overCapacity() ? HudPanel.CRITICAL : HudPanel.PRIMARY);
        panel.line(Component.translatable("br.hud.members",
                ClientStressState.members().size()), HudPanel.DETAIL);
        if (!ClientStressState.shells().isEmpty()) {
            panel.line(Component.translatable("br.hud.plates",
                    ClientStressState.shells().size()), HudPanel.DETAIL);
        }
        // Blocks that reached the server and came back in no element. Silence here was the
        // second half of "blocks suddenly stop taking part": the first half was the chunk
        // truncation above, and this is the half where the block arrived and still did not
        // appear in the answer. One line per reason, because the reasons call for
        // different moves — widen the slab, ground the structure, or nothing at all.
        for (UnassignedReason r : UnassignedReason.values()) {
            int c = ClientStressState.unassignedCount(r);
            if (c <= 0) continue;
            boolean benign = !r.formsNoElement();
            panel.line(Component.translatable(r.translationKey(), c), benign ? HudPanel.DETAIL : HudPanel.WARNING);
        }
        // Part of what the server tracks was in a chunk it could not read, and the pieces
        // standing against that boundary were left uncoloured. Saying so is the whole
        // point of #74: the previous behaviour was a confident number about a structure
        // the engine had only seen part of.
        if (ClientStressState.truncatedBlocks() > 0) {
            panel.line(Component.translatable("br.hud.model_incomplete",
                    ClientStressState.truncatedBlocks()), HudPanel.WARNING);
        }
        // Without this the contour is only ordinal. With it, a colour can be read as MPa.
        panel.line(Component.translatable("br.hud.scale",
                String.format(Locale.ROOT, "%.2f", ClientStressState.colourScaleMpa())), HudPanel.DETAIL);

        panel.gap();
        Optional<MemberSnapshot> focus = ClientStressState.focusedMember();
        if (focus.isPresent()) {
            drawFocus(panel, focus.get());
            return;
        }

        Optional<ShellSnapshot> plate = ClientStressState.focusedShell();
        if (plate.isPresent()) {
            drawPlateFocus(panel, plate.get());
            return;
        }

        panel.line(Component.translatable("br.hud.aim"), HudPanel.DETAIL);
        panel.gap();
        drawLegend(panel, mode);
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
    private static void drawPlateFocus(HudPanel panel, ShellSnapshot s) {
        panel.line(Component.translatable("br.hud.focus_plate",
                s.id(), s.plate(), String.format(Locale.ROOT, "%.0f", s.thicknessMm())), HudPanel.FOCUS);

        panel.line(Component.translatable("br.hud.plate_dc",
                String.format(Locale.ROOT, "%.3f", s.dc())), s.overloaded() ? HudPanel.CRITICAL : HudPanel.PRIMARY);

        if (s.display().isPresent()) {
            var f = s.display().get();
            panel.line(Component.translatable("br.hud.plate_faces",
                    String.format(Locale.ROOT, "%+.2f", f.signedPrincipal(0, 0, +1)),
                    String.format(Locale.ROOT, "%+.2f", f.signedPrincipal(0, 0, -1))), HudPanel.PRIMARY);
        }

        // Said out loud, because the recovered number is the one the demand is based on
        // and it is NOT what the element reported at its corner. A reader who compares the
        // two should be told which they are looking at.
        if (s.edgeRecovered()) {
            panel.line(Component.translatable("br.hud.plate_recovered"), HudPanel.WARNING);
        }
    }

    /** Everything about the one member being looked at. */
    private static void drawFocus(HudPanel panel, MemberSnapshot m) {
        panel.line(Component.translatable("br.hud.focus", m.id(), m.section()), HudPanel.FOCUS);

        if (ClientStressState.withheld(m)) {
            // The number exists, and it is about a structure with a piece missing. Showing
            // it greyed would still be showing it, and a player reads a greyed number as a
            // number. So the reason goes here instead of the ratio (N14-c).
            panel.line(Component.translatable("br.hud.focus_withheld"), HudPanel.WARNING);
        } else {
            panel.line(Component.translatable("br.hud.focus_dc",
                    String.format(Locale.ROOT, "%.3f", m.dc()),
                    Component.translatable("br.fibre." + m.governingFibre().name().toLowerCase(Locale.ROOT))), m.isOverloaded() ? HudPanel.CRITICAL : HudPanel.PRIMARY);
        }

        Optional<StressStation> st = ClientStressState.focusedStation();
        Optional<SectionDiagram> sd = ClientStressState.focusedSection();
        if (st.isEmpty() || sd.isEmpty()) return;

        panel.line(Component.translatable("br.hud.at_x",
                String.format(Locale.ROOT, "%.2f", st.get().xMm() / 1000.0)), HudPanel.DETAIL);

        panel.gap();
        panel.diagram(sd.get(), ClientStressState.palette());
    }

    /**
     * The legend for the lens actually being used.
     *
     * <p>It printed the utilisation legend in every mode, which made the material lens
     * look like a broken utilisation lens rather than a different question — and the
     * material lens was itself drawing stress at the time, so the two wrongs agreed.
     */
    private static void drawLegend(HudPanel panel, ScanMode mode) {
        List<StressPalette.LegendStop> stops = switch (mode) {
            case MATERIAL -> StressPalette.materialLegend();
            case STRESS -> ClientStressState.palette().stressLegend();
            default -> StressPalette.utilizationLegend();
        };
        stops.forEach(panel::legend);
    }
}
