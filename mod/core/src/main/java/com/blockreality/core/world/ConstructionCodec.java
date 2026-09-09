package com.blockreality.core.world;

import com.blockreality.api.geom.BlockKey;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded, canonical saved object metadata. This is not the engine or network protocol. */
final class ConstructionCodec {
    static final int MAX_BYTES = 128 * 1024 * 1024;
    private static final int MAGIC = 0x42524f42, VERSION = 1;
    private ConstructionCodec() { }
    static byte[] encode(ConstructionLedger ledger) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(VERSION);
            var graph = ledger.graph();
            out.writeLong(graph.namespace().getMostSignificantBits()); out.writeLong(graph.namespace().getLeastSignificantBits());
            out.writeLong(ledger.epoch); out.writeLong(ledger.completedEpoch); out.writeLong(graph.nextId());
            out.writeUTF(ledger.failure);
            out.writeInt(ledger.cells.size());
            for (BlockKey p : sorted(ledger.cells.keySet())) { pos(out, p); declaration(out, ledger.cells.get(p)); }
            out.writeInt(ledger.destroyed.size()); for (BlockKey p : sorted(ledger.destroyed)) pos(out, p);
            out.writeInt(graph.records().size());
            for (var a : graph.records().values()) {
                out.writeLong(a.id()); declaration(out, a.declaration());
                out.writeInt(a.parents().size()); for (long parent : a.parents()) out.writeLong(parent);
                out.writeInt(a.cells().size()); for (BlockKey p : a.cells()) pos(out, p);
            }
            out.flush(); byte[] body = bytes.toByteArray();
            if (body.length > MAX_BYTES - 32) throw ConstructionLedger.invalid();
            bytes.write(hash(body)); return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    static ConstructionLedger decode(byte[] bytes) {
        if (bytes.length < 94 || bytes.length > MAX_BYTES) throw ConstructionLedger.invalid();
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(hash(body), Arrays.copyOfRange(bytes, body.length, bytes.length))) throw ConstructionLedger.invalid();
        try {
            var in = new DataInputStream(new ByteArrayInputStream(body));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw ConstructionLedger.invalid();
            UUID namespace = new UUID(in.readLong(), in.readLong());
            long epoch = in.readLong(), completed = in.readLong(), nextId = in.readLong();
            String failure = in.readUTF();
            if (epoch < 0 || completed < 0 || completed > epoch || failure.length() > 256) throw ConstructionLedger.invalid();
            var cells = new HashMap<BlockKey, ConstructionDeclaration>();
            int size = count(in, WorldCellIndex.MAX_CELLS); BlockKey previous = null;
            for (int i = 0; i < size; i++) {
                BlockKey p = ordered(in, previous); cells.put(p, declaration(in)); previous = p;
            }
            var destroyed = new HashSet<BlockKey>(); size = count(in, WorldCellIndex.MAX_CELLS); previous = null;
            for (int i = 0; i < size; i++) { BlockKey p = ordered(in, previous); destroyed.add(p); previous = p; }
            var records = new TreeMap<Long, ConstructionLedger.Artifact>(); size = count(in, ConstructionLedger.MAX_RECORDS);
            long priorId = 0, references = 0, occupied = 0;
            for (int i = 0; i < size; i++) {
                long id = in.readLong(); if (id <= priorId) throw ConstructionLedger.invalid(); priorId = id;
                var declaration = declaration(in); int parentsCount = count(in, ConstructionLedger.MAX_PARENTS);
                references += parentsCount; if (references > ConstructionLedger.MAX_PARENTS) throw ConstructionLedger.invalid();
                var parents = new ArrayList<Long>(); for (int j = 0; j < parentsCount; j++) parents.add(in.readLong());
                int cellCount = count(in, WorldCellIndex.MAX_CELLS); occupied += cellCount;
                if (occupied > WorldCellIndex.MAX_CELLS) throw ConstructionLedger.invalid();
                var positions = new ArrayList<BlockKey>(); previous = null;
                for (int j = 0; j < cellCount; j++) { BlockKey p = ordered(in, previous); positions.add(p); previous = p; }
                records.put(id, new ConstructionLedger.Artifact(id, declaration, parents, positions));
            }
            if (in.available() != 0 || completed == epoch && !destroyed.isEmpty()) throw ConstructionLedger.invalid();
            var graph = new ConstructionLedger.Graph(namespace, nextId, records);
            if (completed == epoch && !graph.owners().keySet().equals(cells.keySet())) throw ConstructionLedger.invalid();
            var ledger = new ConstructionLedger(graph);
            ledger.cells.putAll(cells); ledger.destroyed.addAll(destroyed);
            ledger.epoch = epoch; ledger.completedEpoch = completed; ledger.failure = failure;
            return ledger;
        } catch (IOException e) { throw ConstructionLedger.invalid(); }
    }
    private static List<BlockKey> sorted(Collection<BlockKey> cells) { return cells.stream().sorted(ConstructionLedger.ORDER).toList(); }
    private static void pos(DataOutputStream out, BlockKey p) throws IOException { out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z()); }
    private static BlockKey ordered(DataInputStream in, BlockKey previous) throws IOException {
        BlockKey p = new BlockKey(in.readInt(), in.readInt(), in.readInt()); ConstructionLedger.validatePosition(p);
        if (previous != null && ConstructionLedger.ORDER.compare(previous, p) >= 0) throw ConstructionLedger.invalid();
        return p;
    }
    private static void declaration(DataOutputStream out, ConstructionDeclaration d) throws IOException {
        out.writeUTF(d.material()); out.writeUTF(d.section()); out.writeInt(d.axis());
    }
    private static ConstructionDeclaration declaration(DataInputStream in) throws IOException {
        return new ConstructionDeclaration(in.readUTF(), in.readUTF(), in.readInt());
    }
    private static int count(DataInputStream in, int limit) throws IOException {
        int n = in.readInt(); if (n < 0 || n > limit) throw ConstructionLedger.invalid(); return n;
    }
    private static byte[] hash(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
