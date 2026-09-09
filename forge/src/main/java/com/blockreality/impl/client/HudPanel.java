package com.blockreality.impl.client;

import com.blockreality.api.render.StressPalette;
import com.blockreality.core.render.SectionDiagram;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Measured, bounded drawing surface for the held instrument. Contains no verdict logic. */
@OnlyIn(Dist.CLIENT)
final class HudPanel {
    static final int PRIMARY = 0xFFFFFF, DETAIL = 0xAAAAAA, FOCUS = 0x9FE8FF;
    static final int CRITICAL = 0xFF6B6B, WARNING = 0xC8A24A, MECHANISM = 0xFFCC00;
    private static final int EDGE = 6, INSET = 6, ROW = 12, GAP = 4;
    private static final int BACKPLATE = 0xEB141B22;
    private static final int PLOT_WIDTH = 96, PLOT_HEIGHT = 56;
    private final Font font;
    private final int width, contentWidth, availableHeight;
    private final List<Entry> entries = new ArrayList<>();

    @FunctionalInterface private interface Drawing { void draw(GuiGraphics g, int x, int y); }
    private record Entry(int height, Drawing drawing) { }

    HudPanel(Minecraft mc) {
        font = mc.font;
        width = Math.min(304, (int) (mc.getWindow().getGuiScaledWidth() * .48));
        contentWidth = Math.max(1, width - INSET * 2);
        availableHeight = Math.max(0, mc.getWindow().getGuiScaledHeight() - EDGE - 44);
    }

    void line(Component text, int colour) {
        for (var line : font.split(text, contentWidth)) {
            entries.add(new Entry(ROW, (g, x, y) -> g.drawString(font, line, x, y, colour, false)));
        }
    }

    void gap() { entries.add(new Entry(GAP, (g, x, y) -> { })); }

    void legend(StressPalette.LegendStop stop) {
        var lines = font.split(Component.translatable(stop.translationKey()), Math.max(1, contentWidth - 12));
        entries.add(new Entry(Math.max(ROW, lines.size() * ROW), (g, x, y) -> {
            g.fill(x, y + 1, x + 8, y + 9, stop.colour().argb(1f));
            for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 12, y + i * ROW, PRIMARY, false);
        }));
    }

    void diagram(SectionDiagram diagram, StressPalette palette) {
        int labelWidth = Math.max(1, contentWidth - PLOT_WIDTH - GAP);
        var top = font.split(Component.translatable("br.section.top", Component.translatable(diagram.topLabelKey()),
                String.format(java.util.Locale.ROOT, "%+.2f", diagram.topSigmaMpa())), labelWidth);
        var bottom = font.split(Component.translatable("br.section.bottom", Component.translatable(diagram.bottomLabelKey()),
                String.format(java.util.Locale.ROOT, "%+.2f", diagram.bottomSigmaMpa())), labelWidth);
        var neutral = diagram.neutralFraction().isPresent()
                ? font.split(Component.translatable("br.section.neutral"), labelWidth) : List.<net.minecraft.util.FormattedCharSequence>of();
        int height = Math.max(PLOT_HEIGHT + 1, (top.size() + neutral.size() + bottom.size()) * ROW + 2 * GAP);
        entries.add(new Entry(height, (g, x, y) -> {
            drawPlot(g, x, y, diagram, palette);
            int labelX = x + PLOT_WIDTH + GAP;
            int labelY = y;
            for (var line : top) { g.drawString(font, line, labelX, labelY, PRIMARY, false); labelY += ROW; }
            labelY += GAP;
            for (var line : neutral) { g.drawString(font, line, labelX, labelY, DETAIL, false); labelY += ROW; }
            labelY = Math.max(labelY + GAP, y + height - bottom.size() * ROW);
            for (var line : bottom) { g.drawString(font, line, labelX, labelY, PRIMARY, false); labelY += ROW; }
        }));
    }

    void render(GuiGraphics g) {
        if (width <= INSET * 2 || availableHeight < INSET * 2 + ROW) return;
        int desired = entries.stream().mapToInt(Entry::height).sum();
        int height = Math.min(desired + INSET * 2, availableHeight);
        boolean omitted = desired + INSET * 2 > availableHeight;
        var footer = font.split(Component.translatable("br.hud.more_details"), contentWidth);
        int contentBottom = EDGE + height - INSET - (omitted ? footer.size() * ROW + GAP : 0);
        g.fill(EDGE, EDGE, EDGE + width, EDGE + height, BACKPLATE);
        int y = EDGE + INSET;
        for (Entry entry : entries) {
            if (y + entry.height() > contentBottom) break;
            entry.drawing().draw(g, EDGE + INSET, y);
            y += entry.height();
        }
        if (omitted) {
            y = contentBottom + GAP;
            for (var line : footer) { g.drawString(font, line, EDGE + INSET, y, WARNING, false); y += ROW; }
        }
    }

    /** Existing display interpolation of native samples, unchanged from StressHud. */
    private static void drawPlot(GuiGraphics g, int x, int y, SectionDiagram d, StressPalette p) {
        int axisX = x + PLOT_WIDTH / 2;
        double peak = d.peakMagnitudeMpa();
        if (peak <= 0) peak = 1;
        g.fill(x, y, x + PLOT_WIDTH, y + PLOT_HEIGHT + 1, 0xFFCED7DF);
        for (int row = 0; row < PLOT_HEIGHT; row++) {
            double fraction = (double) row / (PLOT_HEIGHT - 1);
            double sigma = d.sigmaAt(fraction);
            int length = (int) Math.round(Math.abs(sigma) / peak * (PLOT_WIDTH / 2.0 - 2));
            if (length <= 0) continue;
            int colour = p.signedStress(sigma, peak).argb(.85f);
            if (sigma > 0) g.fill(axisX + 1, y + row, axisX + 1 + length, y + row + 1, colour);
            else g.fill(axisX - length, y + row, axisX, y + row + 1, colour);
        }
        g.fill(x, y, x + PLOT_WIDTH, y + 1, 0xFF768694);
        g.fill(x, y + PLOT_HEIGHT, x + PLOT_WIDTH, y + PLOT_HEIGHT + 1, 0xFF768694);
        g.fill(axisX, y, axisX + 1, y + PLOT_HEIGHT, 0xFF768694);
        d.neutralFraction().ifPresent(f -> {
            int ny = y + (int) Math.round(f * (PLOT_HEIGHT - 1));
            g.fill(x, ny, x + PLOT_WIDTH, ny + 1, 0xFF536575);
        });
    }
}
