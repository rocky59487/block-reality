package com.blockreality.impl.client;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ScanMode;
import com.blockreality.api.render.Rgb;
import com.blockreality.api.render.StressPalette;
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

/**
 * The legend, the current lens, and — when it applies — the reason the numbers on screen
 * cannot be trusted.
 *
 * <p>A stress overlay without a legend is a picture, not a measurement. The colour ramp is
 * linear in stress, so the legend is what turns it back into MPa, and a screenshot in a
 * paper needs it to be readable.
 *
 * <p>The three states that must never be confused are given three different messages:
 * a current result, a stale one, and a structure that is not a structure at all.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, value = Dist.CLIENT)
public final class StressHud {

    private StressHud() { }

    private static final int PAD = 6;
    private static final int SWATCH = 8;

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
                Component.translatable("br.scan.mode." + mode.name().toLowerCase(java.util.Locale.ROOT))),
                x, y, 0xFFFFFF);
        y += 12;

        if (!ClientStressState.engineStatus().isEmpty()) {
            g.drawString(mc.font, Component.translatable("br.hud.engine_unavailable",
                    ClientStressState.engineDetail()), x, y, 0xFF6B6B);
            return;
        }
        if (!ClientStressState.hasData()) {
            g.drawString(mc.font, Component.translatable("br.hud.no_data"), x, y, 0xAAAAAA);
            return;
        }
        if (ClientStressState.singular()) {
            // Not a failure and not a safe structure: there is nothing holding it up, so
            // there are no stresses to report. Saying "0 MPa" here would be a lie.
            g.drawString(mc.font, Component.translatable("br.hud.mechanism"), x, y, 0xFFCC00);
            return;
        }

        g.drawString(mc.font, Component.translatable("br.hud.maxdc",
                String.format("%.3f", ClientStressState.maxDc())), x, y,
                ClientStressState.maxDc() > 1.0 ? 0xFF6B6B : 0xFFFFFF);
        y += 12;

        y = drawLegend(g, mc, x, y, mode);

        List<MemberSnapshot> members = ClientStressState.members();
        g.drawString(mc.font, Component.translatable("br.hud.members", members.size()), x, y, 0xAAAAAA);
    }

    private static int drawLegend(GuiGraphics g, Minecraft mc, int x, int y, ScanMode mode) {
        List<StressPalette.LegendStop> stops = mode == ScanMode.UTILIZATION
                ? StressPalette.utilizationLegend()
                : ClientStressState.palette().stressLegend();

        for (StressPalette.LegendStop stop : stops) {
            Rgb c = stop.colour();
            g.fill(x, y + 1, x + SWATCH, y + 1 + SWATCH, c.argb(1f));
            g.drawString(mc.font, Component.translatable(stop.translationKey()),
                    x + SWATCH + 4, y, 0xFFFFFF);
            y += 11;
        }

        if (mode != ScanMode.UTILIZATION) {
            double peak = 0;
            for (MemberSnapshot m : ClientStressState.members()) {
                peak = Math.max(peak, m.peakMagnitudeMpa());
            }
            // The scale itself: without it, the colours are only ordinal.
            g.drawString(mc.font, Component.translatable("br.hud.peak", String.format("%.2f", peak)),
                    x, y, 0xAAAAAA);
            y += 11;
        }
        return y;
    }
}
