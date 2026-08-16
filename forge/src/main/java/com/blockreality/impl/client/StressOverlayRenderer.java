package com.blockreality.impl.client;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ScanMode;
import com.blockreality.api.StressStation;
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
 * <h2>One member in detail, everything else in one colour</h2>
 * The first version drew four stress ribbons for every member at once. On a single beam
 * that is informative; on an actual building it is a haze that answers no question, and
 * the ribbons are 0.2 blocks from the centreline so they overlap into mush at any distance.
 *
 * <p>So the overlay now works the way a diagnostic instrument does. Every member gets
 * <strong>one</strong> line along its axis, coloured by how close it is to capacity — that
 * scales to hundreds of members and answers "where is the problem". The member under the
 * crosshair additionally gets its full section detail, and the HUD draws its section
 * diagram in words and numbers. That answers "what is happening here", for one member at a
 * time, which is the only scale at which the question is meaningful.
 *
 * <p>The lines are billboarded — widened perpendicular to the view — so a member stays
 * visible edge-on instead of vanishing when you line up with it.
 *
 * <p>The renderer still performs no engineering: no sign conversion, no axis transform, no
 * normalisation, no palette decision. Those happened upstream, once each, under test.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, value = Dist.CLIENT)
public final class StressOverlayRenderer {

    private StressOverlayRenderer() { }

    /** Half-width of a member axis line, blocks. */
    private static final float AXIS_HALF_WIDTH = 0.06f;

    /** Half-width of a fibre ribbon on the focused member. */
    private static final float FIBRE_HALF_WIDTH = 0.05f;

    /** Members further than this are not drawn at all. */
    private static final double DRAW_DISTANCE = 128.0;

    private static final float ALPHA = 0.9f;
    private static final float CONTEXT_ALPHA = 0.35f;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || !holdingGlasses(player)) return;
        if (!ClientStressState.hasData()) return;

        List<MemberSnapshot> members = ClientStressState.members();
        List<StressRibbon> ribbons = ClientStressState.ribbons();
        if (members.isEmpty()) return;

        Vec3 cam = event.getCamera().getPosition();
        Vec3d eye = new Vec3d(cam.x, cam.y, cam.z);
        int focus = ClientStressState.focusedMemberId();

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        // DEPTH TEST OFF, and this is not a shortcut. A steel_rect_200x400 section puts its
        // extreme fibres 0.2 blocks from the centreline — well inside the one-metre cube
        // that represents the member. With depth testing on, everything here would be
        // buried inside opaque blocks and the overlay would render perfectly and show
        // nothing at all. It is also what the tool is for: the glasses look INTO the
        // structure, and section forces do not live on the surface.
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        ScanMode mode = ClientStressState.mode();

        for (int i = 0; i < members.size(); i++) {
            MemberSnapshot member = members.get(i);
            List<StressStation> st = member.stations();
            if (st.isEmpty()) continue;
            if (distanceTo(eye, st) > DRAW_DISTANCE) continue;

            boolean focused = member.id() == focus;

            // Every member, every mode: one line, one colour, utilisation.
            // Even in the stress lens this is the layer that says "look over there".
            Rgb axisColour = StressPalette.utilization(member.dc());
            float alpha = focused ? 1.0f : (mode == ScanMode.UTILIZATION ? ALPHA : CONTEXT_ALPHA);
            float width = focused ? AXIS_HALF_WIDTH * 1.8f : AXIS_HALF_WIDTH;
            drawAxis(buf, m, eye, st, axisColour, alpha, width);

            // The focused member, in the stress lens, also gets its section detail.
            if (focused && mode != ScanMode.UTILIZATION && i < ribbons.size()) {
                drawFibres(buf, m, eye, ribbons.get(i));
            }
        }

        BufferUploader.drawWithShader(buf.end());

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /** One billboarded line down the member's centreline. */
    private static void drawAxis(BufferBuilder buf, Matrix4f m, Vec3d eye,
                                 List<StressStation> stations, Rgb colour, float alpha, float halfWidth) {
        for (int q = 0; q + 1 < stations.size(); q++) {
            Vec3d a = blocks(stations.get(q).centroidMm());
            Vec3d b = blocks(stations.get(q + 1).centroidMm());
            billboardQuad(buf, m, eye, a, b, colour, colour, alpha, halfWidth);
        }
    }

    /** The four extreme fibres of the focused member, each in its own signed colour. */
    private static void drawFibres(BufferBuilder buf, Matrix4f m, Vec3d eye, StressRibbon ribbon) {
        for (StressRibbon.Band b : ribbon.bands()) {
            billboardQuad(buf, m, eye, b.from(), b.to(),
                    b.fromColour(), b.toColour(), ALPHA, FIBRE_HALF_WIDTH);
        }
        Rgb grey = ClientStressState.palette().zeroColour();
        List<Vec3d> na = ribbon.neutralAxis();
        for (int i = 0; i + 1 < na.size(); i++) {
            billboardQuad(buf, m, eye, na.get(i), na.get(i + 1), grey, grey, 0.6f, 0.02f);
        }
    }

    /**
     * A quad from {@code a} to {@code b}, widened perpendicular to both the segment and the
     * direction to the camera.
     *
     * <p>Billboarding rather than using a fixed offset: a flat ribbon disappears when you
     * view it edge-on, which for a horizontal beam is exactly where a player stands.
     */
    private static void billboardQuad(BufferBuilder buf, Matrix4f m, Vec3d eye,
                                      Vec3d a, Vec3d b, Rgb ca, Rgb cb, float alpha, float halfWidth) {
        Vec3d axis = sub(b, a);
        if (axis.length() < 1e-9) return;

        Vec3d toEye = sub(eye, a);
        Vec3d w = cross(axis, toEye).normalised();
        if (w.length() < 1e-9) {
            // Looking exactly down the segment. Any perpendicular will do.
            w = cross(axis, new Vec3d(0, 1, 0)).normalised();
            if (w.length() < 1e-9) w = new Vec3d(1, 0, 0);
        }
        w = w.scaled(halfWidth);

        vertex(buf, m, a.x() - w.x(), a.y() - w.y(), a.z() - w.z(), ca, alpha);
        vertex(buf, m, a.x() + w.x(), a.y() + w.y(), a.z() + w.z(), ca, alpha);
        vertex(buf, m, b.x() + w.x(), b.y() + w.y(), b.z() + w.z(), cb, alpha);
        vertex(buf, m, b.x() - w.x(), b.y() - w.y(), b.z() - w.z(), cb, alpha);
    }

    private static double distanceTo(Vec3d eye, List<StressStation> stations) {
        Vec3d p = blocks(stations.get(0).centroidMm());
        return sub(p, eye).length();
    }

    private static void vertex(BufferBuilder buf, Matrix4f m, double x, double y, double z, Rgb c, float a) {
        buf.vertex(m, (float) x, (float) y, (float) z)
           .color(c.r() / 255f, c.g() / 255f, c.b() / 255f, a)
           .endVertex();
    }

    private static Vec3d blocks(Vec3d mm) {
        return new Vec3d(mm.x() / 1000.0, mm.y() / 1000.0, mm.z() / 1000.0);
    }

    private static Vec3d sub(Vec3d a, Vec3d b) {
        return new Vec3d(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
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
