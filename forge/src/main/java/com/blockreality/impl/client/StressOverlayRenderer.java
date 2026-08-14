package com.blockreality.impl.client;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ScanMode;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.api.render.Rgb;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.render.StressRibbon;
import com.blockreality.impl.BRContent;
import com.blockreality.impl.BlockRealityMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws the stress field in the world.
 *
 * <p>The interesting property of this class is how little it knows. It receives positions
 * in blocks and colours in RGB and pushes vertices. It performs no sign conversion, no
 * axis transform, no normalisation, no unit conversion and no palette lookup — those all
 * happened once, upstream, where they are unit-tested. Both FrameCore issues in
 * ENGINE_FINDINGS.md were axis-pairing errors, and the defence against making a third one
 * here is to leave this file no axes to pair.
 *
 * <p>Drawn at {@code AFTER_TRANSLUCENT_BLOCKS} with depth writes off, so the overlay sits
 * over the world without punching holes in what is behind it.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, value = Dist.CLIENT)
public final class StressOverlayRenderer {

    private StressOverlayRenderer() { }

    /** Half-width of a ribbon, in blocks. Wide enough to read, narrow enough to see past. */
    private static final float HALF_WIDTH = 0.09f;

    private static final float ALPHA = 0.85f;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || !holdingGlasses(player)) return;
        if (!ClientStressState.hasData()) return;

        List<StressRibbon> ribbons = ClientStressState.ribbons();
        if (ribbons.isEmpty()) return;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        // DEPTH TEST OFF, and this is not a shortcut.
        //
        // A steel_rect_200x400 section puts its extreme fibres 0.2 and 0.1 blocks from the
        // centreline — well inside the one-metre cube that represents the member. Drawn
        // with depth testing on, every ribbon would be buried inside an opaque block and
        // the overlay would render perfectly and show nothing at all.
        //
        // It is also what the tool is for: the stress glasses look INTO the structure.
        // Section forces do not live on the surface.
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        ScanMode mode = ClientStressState.mode();
        for (int i = 0; i < ribbons.size(); i++) {
            StressRibbon r = ribbons.get(i);
            if (mode == ScanMode.UTILIZATION) {
                drawUtilization(buf, m, r, ClientStressState.members().get(i));
            } else {
                drawSignedStress(buf, m, r);
            }
        }

        BufferUploader.drawWithShader(buf.end());

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /**
     * The signed-stress lens: every fibre drawn in its own colour, so a bent member shows
     * tension on one face and compression on the other rather than a single hue.
     */
    private static void drawSignedStress(BufferBuilder buf, Matrix4f m, StressRibbon r) {
        for (StressRibbon.Band b : r.bands()) {
            quad(buf, m, b.from(), b.to(), b.fibreDir(), b.fromColour(), b.toColour(), ALPHA);
        }
        drawNeutralAxis(buf, m, r);
    }

    /** The utilisation lens: one colour per member, keyed on D/C. */
    private static void drawUtilization(BufferBuilder buf, Matrix4f m, StressRibbon r, MemberSnapshot member) {
        Rgb c = StressPalette.utilization(member.dc());
        for (StressRibbon.Band b : r.bands()) {
            quad(buf, m, b.from(), b.to(), b.fibreDir(), c, c, ALPHA);
        }
    }

    /**
     * The neutral axis, where the section changes sign.
     *
     * <p>Stations with no neutral axis leave a gap in the line, and that gap is
     * information: it marks where the whole section went into tension or compression.
     */
    private static void drawNeutralAxis(BufferBuilder buf, Matrix4f m, StressRibbon r) {
        List<Vec3d> pts = r.neutralAxis();
        Rgb grey = ClientStressState.palette().zeroColour();
        for (int i = 0; i + 1 < pts.size(); i++) {
            quad(buf, m, pts.get(i), pts.get(i + 1), new Vec3d(0, 1, 0), grey, grey, 0.55f);
        }
    }

    /**
     * One flat quad from {@code a} to {@code b}, widened perpendicular to both the member
     * axis and the fibre direction.
     *
     * <p>Using the fibre direction rather than a fixed world axis is what makes this work
     * for a vertical column as well as a horizontal beam: the strip always lies flat
     * against the face it belongs to.
     */
    private static void quad(BufferBuilder buf, Matrix4f m, Vec3d a, Vec3d b, Vec3d fibreDir,
                             Rgb ca, Rgb cb, float alpha) {
        Vec3d axis = new Vec3d(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
        if (axis.length() < 1e-9) return;

        Vec3d w = cross(axis, fibreDir).normalised();
        if (w.length() < 1e-9) {
            // Degenerate only if the fibre direction is parallel to the member, which the
            // engine never produces. Skip rather than draw a zero-area quad.
            return;
        }
        w = w.scaled(HALF_WIDTH);

        vertex(buf, m, a.x() - w.x(), a.y() - w.y(), a.z() - w.z(), ca, alpha);
        vertex(buf, m, a.x() + w.x(), a.y() + w.y(), a.z() + w.z(), ca, alpha);
        vertex(buf, m, b.x() + w.x(), b.y() + w.y(), b.z() + w.z(), cb, alpha);
        vertex(buf, m, b.x() - w.x(), b.y() - w.y(), b.z() - w.z(), cb, alpha);
    }

    private static void vertex(BufferBuilder buf, Matrix4f m, double x, double y, double z, Rgb c, float a) {
        buf.vertex(m, (float) x, (float) y, (float) z)
           .color(c.r() / 255f, c.g() / 255f, c.b() / 255f, a)
           .endVertex();
    }

    private static Vec3d cross(Vec3d a, Vec3d b) {
        return new Vec3d(
                a.y() * b.z() - a.z() * b.y(),
                a.z() * b.x() - a.x() * b.z(),
                a.x() * b.y() - a.y() * b.x());
    }

    private static boolean holdingGlasses(Player p) {
        return p.getMainHandItem().is(BRContent.STRESS_GLASSES.get())
                || p.getOffhandItem().is(BRContent.STRESS_GLASSES.get());
    }
}
