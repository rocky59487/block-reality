package com.blockreality.core.transaction;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.world.ConstructionDeclaration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

/** Strict metadata values inside the journal's checksummed envelope. Not a network codec. */
final class MetadataCodec {
    enum Operation { BUILD, EDIT, UNDO }
    record Descriptor(Operation operation, String dimension, boolean creative, UUID original) {
        Descriptor {
            Objects.requireNonNull(operation); MetadataCodec.dimension(dimension);
            if ((operation == Operation.UNDO) != (original != null) || operation == Operation.EDIT && creative) throw invalid();
        }
    }
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_.-]+");
    private MetadataCodec() { }
    static void dimension(String value) {
        if (value == null || value.length() > 256 || !DIMENSION.matcher(value).matches()) throw invalid();
    }
    static String revisionKey(String dimension) {
        dimension(dimension);
        try { return "revision/" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(dimension.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static Value number(long value) {
        if (value < 0) throw invalid();
        return encode(out -> out.writeLong(value));
    }
    static long number(Value value) { return decode(value, in -> { long result = in.readLong(); if (result < 0) throw invalid(); return result; }); }
    static Value descriptor(Descriptor d) {
        return encode(out -> {
            out.writeInt(0x42524d54); out.writeInt(1); out.writeByte(d.operation().ordinal());
            text(out,d.dimension()); out.writeByte(d.creative() ? 1 : 0);
            out.writeByte(d.original() != null ? 1 : 0); if (d.original() != null) uuid(out,d.original());
        });
    }
    static Descriptor descriptor(Value value) {
        return decode(value, in -> {
            header(in,0x42524d54); int op = in.readUnsignedByte(); if (op >= Operation.values().length) throw invalid();
            String dimension = text(in,256); boolean creative = flag(in); UUID original = flag(in) ? uuid(in) : null;
            return new Descriptor(Operation.values()[op],dimension,creative,original);
        });
    }
    static Value piece(ManufacturedPiece p) {
        return encode(out -> {
            out.writeInt(0x42524d50); out.writeInt(1); uuid(out,p.id()); uuid(out,p.birthTransaction()); uuid(out,p.actor());
            text(out,p.dimension()); text(out,p.declaration().material()); text(out,p.declaration().section());
            out.writeByte(p.declaration().axis()); out.writeLong(p.birthOrder()); out.writeLong(p.birthRevision());
            out.writeByte(p.status().ordinal()); out.writeInt(p.cells().size());
            for (BlockKey cell : p.cells()) { out.writeInt(cell.x()); out.writeInt(cell.y()); out.writeInt(cell.z()); }
        });
    }
    static ManufacturedPiece piece(Value value) {
        return decode(value, in -> {
            header(in,0x42524d50); UUID id = uuid(in), birth = uuid(in), actor = uuid(in);
            String dimension = text(in,256), material = text(in,64), section = text(in,64);
            if (!TOKEN.matcher(material).matches() || !TOKEN.matcher(section).matches()) throw invalid();
            int axis = in.readUnsignedByte(); long order = in.readLong(), revision = in.readLong();
            int status = in.readUnsignedByte(), count = in.readInt();
            if (status >= ManufacturedPiece.Status.values().length || count < 0 || count > MAX_CELLS
                    || count > in.available()/12) throw invalid();
            var cells = new ArrayList<BlockKey>(count); BlockKey previous = null;
            for (int i = 0; i < count; i++) {
                BlockKey p = new BlockKey(in.readInt(),in.readInt(),in.readInt());
                if (previous != null && ManufacturedPiece.CELL_ORDER.compare(previous,p) >= 0) throw invalid();
                cells.add(p); previous = p;
            }
            return new ManufacturedPiece(id,birth,actor,dimension,new ConstructionDeclaration(material,section,axis),
                    order,revision,ManufacturedPiece.Status.values()[status],cells);
        });
    }
    private static void header(DataInputStream in, int magic) throws IOException {
        if (in.readInt() != magic || in.readInt() != 1) throw invalid();
    }
    private static boolean flag(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte(); if (value > 1) throw invalid(); return value == 1;
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(),in.readLong()); }
    private static void text(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in, int limit) throws IOException {
        int size = in.readInt(); if (size < 1 || size > limit || size > in.available()) throw invalid();
        byte[] bytes = in.readNBytes(size);
        for (byte b : bytes) if (b < 0 || b < 32 || b > 126) throw invalid();
        return new String(bytes,StandardCharsets.US_ASCII);
    }
    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
    @FunctionalInterface private interface Reader<T> { T read(DataInputStream in) throws IOException; }
    private static Value encode(Writer writer) {
        try { var bytes = new ByteArrayOutputStream(); writer.write(new DataOutputStream(bytes)); return Value.of(bytes.toByteArray()); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private static <T> T decode(Value value, Reader<T> reader) {
        if (!value.present()) throw invalid();
        try {
            var in = new DataInputStream(new ByteArrayInputStream(value.bytes())); T result = reader.read(in);
            if (in.available() != 0) throw invalid(); return result;
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated construction metadata",malformed); }
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid construction metadata encoding"); }
}
