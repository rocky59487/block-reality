package com.blockreality.impl.client;

import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellSnapshot;
import com.blockreality.api.ScanMode;
import com.blockreality.api.StressFieldSpec;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.api.render.Rgb;
import com.blockreality.api.render.StressPalette;
import com.blockreality.core.render.ShellMesh;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A stress contour on the surface of the structure, the way a post-processor draws one.
 *
 * <p>The earlier overlay drew four thin ribbons per member: samples of a field, floating
 * inside the blocks. This draws the field itself, on the <strong>outer faces of the
 * blocks</strong> — every face not shared with another structural block gets a colour map
 * evaluated from {@link StressFieldSpec}, so the result is a continuous contour over the
 * whole structure rather than a set of lines through it.
 *
 * <p>Three things make it read like an engineering plot rather than decoration:
 *
 * <ul>
 *   <li><strong>Per-vertex colour on a subdivided face.</strong> Each face is split into a
 *       grid and every grid vertex is evaluated exactly. Stress varies linearly through the
 *       depth and quadratically along a member under distributed load; a single quad per
 *       face would straight-line the parabola and lose the peak.
 *   <li><strong>Interior faces are skipped.</strong> A face shared with another structural
 *       block is not a surface, and drawing it wastes fill rate on something no one can
 *       see — and, worse, makes the outside look muddy where two members meet.
 *   <li><strong>The block is a magnified section.</strong> A one-metre cube stands for a
 *       200×400 section, so a point on the cube maps proportionally onto the section
 *       instead of being extrapolated outside the material.
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, value = Dist.CLIENT)
public final class StressSurfaceRenderer {

    private StressSurfaceRenderer() { }

    /** Subdivisions per face edge. 4 captures the parabola; more is invisible and costs fill. */
    private static final int GRID = 4;

    /** Lifted off the block surface so it does not z-fight with the block texture. */
    private static final double LIFT = 0.002;

    private static final double DRAW_DISTANCE = 128.0;
    private static final float ALPHA = 0.92f;

    /** The six face normals, and the two in-plane axes for each. */
    private static final int[][] FACES = {
            //  nx ny nz    ux uy uz    vx vy vz
            {  0,  1,  0,   1, 0, 0,   0, 0, 1 },   // +Y
            {  0, -1,  0,   1, 0, 0,   0, 0, 1 },   // -Y
            {  1,  0,  0,   0, 1, 0,   0, 0, 1 },   // +X
            { -1,  0,  0,   0, 1, 0,   0, 0, 1 },   // -X
            {  0,  0,  1,   1, 0, 0,   0, 1, 0 },   // +Z
            {  0,  0, -1,   1, 0, 0,   0, 1, 0 },   // -Z
    };

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || !holdingGlasses(player)) return;
        if (!ClientStressState.hasData()) return;

        List<MemberSnapshot> members = ClientStressState.members();
        if (members.isEmpty()) return;

        // Which cells are occupied, so shared faces can be skipped. Built from every
        // member, not just the one being drawn: two members meeting at a joint hide each
        // other's end faces exactly as the blocks do.
        Set<Long> occupied = ClientStressState.occupiedCells();

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        ScanMode mode = ClientStressState.mode();
        double scale = ClientStressState.colourScaleMpa();
        int focus = ClientStressState.focusedMemberId();

        // Plate facets first: a floor is usually the largest thing on screen, and drawing
        // it before the beams means a beam bearing on it is never hidden behind its slab.
        drawPlates(buf, m, cam, occupied, mode, scale, alpha(focus, -1));

        for (MemberSnapshot member : members) {
            if (member.field().isEmpty() || member.blocks().isEmpty()) continue;
            StressFieldSpec f = member.field().get();

            if (Math.abs(f.originMm().x() / 1000.0 - cam.x) > DRAW_DISTANCE
                    && Math.abs(f.originMm().z() / 1000.0 - cam.z) > DRAW_DISTANCE) continue;

            // In the utilisation lens the whole member takes one colour, so the surface
            // answers "which member is in trouble" instead of "what is happening inside
            // this one". Both are contours; they map different quantities.
            Rgb flat = mode == ScanMode.UTILIZATION ? StressPalette.utilization(member.dc()) : null;
            float alpha = alpha(focus, member.id());

            for (BlockKey b : member.blocks()) {
                drawBlock(buf, m, b, f, occupied, flat, scale, alpha);
            }
        }

        BufferUploader.drawWithShader(buf.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    private static float alpha(int focus, int id) {
        return (focus < 0 || id == focus) ? ALPHA : ALPHA * 0.45f;
    }

    /**
     * The plate contour, drawn on the blocks rather than on the mesh.
     *
     * <p>Three things are going on, and the third is the one that makes it read as an
     * engineering plot rather than as a coloured floor.
     *
     * <ul>
     *   <li>The facet that owns a point is found geometrically ({@link ShellMesh}), so a
     *       block belonging to no facet — a slab's outer ring, which really is half a block
     *       beyond the last node — is CLAMPED to the boundary value rather than left blank
     *       or extrapolated into a stress nothing is carrying.
     *   <li>The two faces of a plate carry equal and opposite bending stress. The top face
     *       of a block is painted from the plate's top surface and the underside from its
     *       bottom, so walking under a floor shows the other half of the answer — which for
     *       a slab in bending is the difference between tension and compression.
     *   <li><strong>The side faces show the through-thickness gradient.</strong> Stress
     *       varies linearly across the plate, so a vertical face is shaded from the bottom
     *       fibre to the top one and the neutral surface appears as the neutral band, at
     *       its real height. That is a picture of the actual stress distribution, and it is
     *       free: it is the same field evaluated at a different z.
     * </ul>
     */
    private static void drawPlates(BufferBuilder buf, Matrix4f m, Vec3 cam,
                                   Set<Long> occupied, ScanMode mode, double scale, float alpha) {
        List<ShellSnapshot> shells = ClientStressState.shells();
        if (shells.isEmpty()) return;

        for (BlockKey b : ClientStressState.plateBlocks()) {
            if (Math.abs(b.x() - cam.x) > DRAW_DISTANCE || Math.abs(b.z() - cam.z) > DRAW_DISTANCE) continue;

            // One facet lookup per block, not per vertex: the block is a metre across and
            // the mesh is a metre wide, so every vertex of it resolves to the same facet.
            var hit = ShellMesh.locate(shells, new Vec3d(b.x() * 1000.0 + 500,
                                                         b.y() * 1000.0 + 500,
                                                         b.z() * 1000.0 + 500));
            if (hit.isEmpty()) continue;
            ShellMesh.Hit h = hit.get();
            Rgb flat = mode == ScanMode.UTILIZATION ? StressPalette.utilization(h.shell().dc()) : null;

            for (int[] face : FACES) {
                if (occupied.contains(key(b.x() + face[0], b.y() + face[1], b.z() + face[2]))) continue;

                double cx = b.x() + 0.5 + face[0] * (0.5 + LIFT);
                double cy = b.y() + 0.5 + face[1] * (0.5 + LIFT);
                double cz = b.z() + 0.5 + face[2] * (0.5 + LIFT);

                for (int i = 0; i < GRID; i++) {
                    for (int j = 0; j < GRID; j++) {
                        double u0 = -0.5 + (double) i / GRID, u1 = -0.5 + (double) (i + 1) / GRID;
                        double v0 = -0.5 + (double) j / GRID, v1 = -0.5 + (double) (j + 1) / GRID;
                        emitPlate(buf, m, cx, cy, cz, face, u0, v0, h, flat, scale, alpha);
                        emitPlate(buf, m, cx, cy, cz, face, u1, v0, h, flat, scale, alpha);
                        emitPlate(buf, m, cx, cy, cz, face, u1, v1, h, flat, scale, alpha);
                        emitPlate(buf, m, cx, cy, cz, face, u0, v1, h, flat, scale, alpha);
                    }
                }
            }
        }
    }

    private static void emitPlate(BufferBuilder buf, Matrix4f m,
                                  double cx, double cy, double cz, int[] face,
                                  double u, double v, ShellMesh.Hit h,
                                  Rgb flat, double scale, float alpha) {
        double x = cx + face[3] * u + face[6] * v;
        double y = cy + face[4] * u + face[7] * v;
        double z = cz + face[5] * u + face[8] * v;

        Rgb c = flat;
        if (c == null) {
            Vec3d p = new Vec3d(x * 1000, y * 1000, z * 1000);
            double[] par = h.field().paramAt(p);
            // Where in the thickness this vertex stands. The block is a magnified plate,
            // so its half-height maps to the plate's half-thickness: the top face reads the
            // top fibre, the underside the bottom one, and a side face sweeps between them.
            double zf = clamp(h.field().offNormalMm(p) / 500.0);
            double sigma = h.field().signedPrincipal(clamp(par[0]), clamp(par[1]), zf);
            c = ClientStressState.palette().signedStress(sigma, scale);
        }
        buf.vertex(m, (float) x, (float) y, (float) z)
           .color(c.r() / 255f, c.g() / 255f, c.b() / 255f, alpha)
           .endVertex();
    }

    private static double clamp(double v) { return v < -1 ? -1 : Math.min(v, 1); }

    private static void drawBlock(BufferBuilder buf, Matrix4f m, BlockKey b, StressFieldSpec f,
                                  Set<Long> occupied, Rgb flat, double scale, float alpha) {
        for (int[] face : FACES) {
            // A face shared with another structural block is interior: not a surface.
            if (occupied.contains(key(b.x() + face[0], b.y() + face[1], b.z() + face[2]))) continue;

            // Face centre in blocks, then the two in-plane half-axes.
            double cx = b.x() + 0.5 + face[0] * (0.5 + LIFT);
            double cy = b.y() + 0.5 + face[1] * (0.5 + LIFT);
            double cz = b.z() + 0.5 + face[2] * (0.5 + LIFT);

            for (int i = 0; i < GRID; i++) {
                for (int j = 0; j < GRID; j++) {
                    double u0 = -0.5 + (double) i / GRID, u1 = -0.5 + (double) (i + 1) / GRID;
                    double v0 = -0.5 + (double) j / GRID, v1 = -0.5 + (double) (j + 1) / GRID;

                    emit(buf, m, cx, cy, cz, face, u0, v0, f, flat, scale, alpha);
                    emit(buf, m, cx, cy, cz, face, u1, v0, f, flat, scale, alpha);
                    emit(buf, m, cx, cy, cz, face, u1, v1, f, flat, scale, alpha);
                    emit(buf, m, cx, cy, cz, face, u0, v1, f, flat, scale, alpha);
                }
            }
        }
    }

    private static void emit(BufferBuilder buf, Matrix4f m,
                             double cx, double cy, double cz, int[] face,
                             double u, double v, StressFieldSpec f,
                             Rgb flat, double scale, float alpha) {
        double x = cx + face[3] * u + face[6] * v;
        double y = cy + face[4] * u + face[7] * v;
        double z = cz + face[5] * u + face[8] * v;

        Rgb c = flat;
        if (c == null) {
            // Evaluated exactly at this vertex, in millimetres, against the member's own
            // frame. The GPU interpolates between vertices, which is why the grid matters.
            double sigma = f.sigmaAtWorldMm(new Vec3d(x * 1000, y * 1000, z * 1000), 500);
            c = ClientStressState.palette().signedStress(sigma, scale);
        }
        buf.vertex(m, (float) x, (float) y, (float) z)
           .color(c.r() / 255f, c.g() / 255f, c.b() / 255f, alpha)
           .endVertex();
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static Set<Long> cellsOf(List<MemberSnapshot> members, List<ShellSnapshot> shells) {
        Set<Long> set = new HashSet<>();
        for (MemberSnapshot m : members) {
            for (BlockKey b : m.blocks()) set.add(key(b.x(), b.y(), b.z()));
        }
        // Plate blocks occupy cells too. Without them the underside of a floor would be
        // drawn where a beam is buried in it, and the two surfaces would fight.
        for (ShellSnapshot s : shells) {
            for (BlockKey b : s.blocks()) set.add(key(b.x(), b.y(), b.z()));
        }
        return set;
    }

    private static boolean holdingGlasses(Player p) {
        return p.getMainHandItem().is(BRContent.STRESS_GLASSES.get())
                || p.getOffhandItem().is(BRContent.STRESS_GLASSES.get());
    }
}
