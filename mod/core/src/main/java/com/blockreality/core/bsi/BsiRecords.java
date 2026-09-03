package com.blockreality.core.bsi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The binary records of BSI v1 (contract {@code bsi.schema.json}, section {@code x-records}) and
 * the canonical order they travel in (section {@code x-canonicalOrder}).
 *
 * <p>Every size here is asserted against the schema by {@code BsiRecordsTest}: a record that
 * silently grows is a wire break that compiles.
 *
 * <p>Doubles go through {@link ByteBuffer#putDouble}, raw IEEE-754. They are never formatted as
 * text on this path — a transport that rewrites a number is not a transport.
 */
public final class BsiRecords {

    public static final int BLOCK_BYTES = 40;
    public static final int ATTR_BYTES = 16;
    public static final int EDIT_BYTES = 41;
    public static final int LOAD_BYTES = 64;
    public static final int BLOCK_RESULT_BYTES = 24;
    public static final int EQUILIBRIUM_BYTES = 56;
    public static final int QUALITY_BYTES = 16;
    public static final int BUCKLING_BYTES = 16;
    public static final int MEMBER_BYTES = 160;
    public static final int MEMBER_BLOCK_BYTES = 12;
    public static final int STATION_BYTES = 88;
    public static final int STATION_F32_BYTES = 44;
    public static final int FACET_BYTES = 280;
    public static final int FACET_SURFACES_BYTES = 256;
    public static final int FACET_SURFACES_F32_BYTES = 128;

    /** Largest coordinate the contract admits (B.4: {@code 2*c+2} must fit an int32). */
    public static final int MAX_COORD = 1073741822;

    private BsiRecords() {}

    /**
     * One world cell. {@code sect < 0} means "the material's default section"; {@code axis} is
     * declared by placement and is mandatory for member-role materials (B.4).
     */
    public record Block(int x, int y, int z, int mat, int sect, int axis, int joint, int axisRot,
                        double fill, double strength) {

        public Block {
            if (x < -MAX_COORD || x > MAX_COORD || y < -MAX_COORD || y > MAX_COORD || z < -MAX_COORD || z > MAX_COORD)
                throw new IllegalArgumentException("coordinate out of contract range at (" + x + "," + y + "," + z + ")");
            if (axis < 0 || axis > 2) throw new IllegalArgumentException("axis " + axis + " at (" + x + "," + y + "," + z + ")");
            if (joint < 0 || joint > 1) throw new IllegalArgumentException("joint " + joint + " at (" + x + "," + y + "," + z + ")");
            if (axisRot < 0 || axisRot > 3) throw new IllegalArgumentException("axisRot " + axisRot + " at (" + x + "," + y + "," + z + ")");
            if (!(fill > 0) || fill > 1) throw new IllegalArgumentException("fill out of (0,1] at (" + x + "," + y + "," + z + ")");
            if (!(strength >= 0) || strength > 1) throw new IllegalArgumentException("strength out of [0,1] at (" + x + "," + y + "," + z + ")");
        }

        public static Block of(int x, int y, int z, int mat, int sect, int axis) {
            return new Block(x, y, z, mat, sect, axis, 0, 0, 1.0, 1.0);
        }

        void write(ByteBuffer b) {
            b.putInt(x).putInt(y).putInt(z).putInt(mat).putInt(sect);
            b.put((byte) axis).put((byte) joint).put((byte) axisRot).put((byte) 0);
            b.putDouble(fill).putDouble(strength);
        }
    }

    /** One applied force on a cell. Moments are zero in v1 and the engine refuses anything else. */
    public record Load(int x, int y, int z, double fx, double fy, double fz) {

        void write(ByteBuffer b) {
            b.putInt(x).putInt(y).putInt(z).putInt(0);
            b.putDouble(fx).putDouble(fy).putDouble(fz);
            b.putDouble(0).putDouble(0).putDouble(0);
        }
    }

    private static final Comparator<Block> BLOCK_ORDER =
            Comparator.comparingInt(Block::x).thenComparingInt(Block::y).thenComparingInt(Block::z);

    /**
     * Blocks in canonical order ((x,y,z) ascending), refusing duplicates by name.
     *
     * <p>The host would sort them anyway, but sorting here means the arena can hand the engine
     * the caller's own bytes with no copy (BSI Part D.2), and it means a duplicate is named at
     * the cell that caused it rather than as a protocol error from far away.
     */
    public static List<Block> canonical(List<Block> blocks) {
        List<Block> out = new ArrayList<>(blocks);
        out.sort(BLOCK_ORDER);
        for (int i = 1; i < out.size(); i++) {
            Block p = out.get(i - 1), q = out.get(i);
            if (p.x() == q.x() && p.y() == q.y() && p.z() == q.z())
                throw new IllegalArgumentException("duplicate block at (" + q.x() + "," + q.y() + "," + q.z() + ")");
        }
        return out;
    }

    /** Blocks as the 40-byte records of B.4, in canonical order. */
    public static byte[] encodeBlocks(List<Block> blocks) {
        List<Block> ordered = canonical(blocks);
        ByteBuffer b = ByteBuffer.allocate(ordered.size() * BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (Block blk : ordered) blk.write(b);
        return b.array();
    }

    /**
     * Loads as the 64-byte records of B.5, in canonical order: (x,y,z) ascending, ties broken by
     * the raw bytes of the record (B.8 rule 2). Two loads on one cell are two loads, not one.
     */
    public static byte[] encodeLoads(List<Load> loads) {
        List<byte[]> recs = new ArrayList<>(loads.size());
        for (Load l : loads) {
            ByteBuffer one = ByteBuffer.allocate(LOAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            l.write(one);
            recs.add(one.array());
        }
        recs.sort((p, q) -> {
            ByteBuffer a = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer b = ByteBuffer.wrap(q).order(ByteOrder.LITTLE_ENDIAN);
            for (int k = 0; k < 3; k++) {
                int c = Integer.compare(a.getInt(k * 4), b.getInt(k * 4));
                if (c != 0) return c;
            }
            return Arrays.compareUnsigned(p, q);
        });
        ByteBuffer out = ByteBuffer.allocate(recs.size() * LOAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (byte[] r : recs) out.put(r);
        return out.array();
    }
}
