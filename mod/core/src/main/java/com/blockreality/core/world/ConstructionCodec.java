package com.blockreality.core.world;

import com.blockreality.api.geom.BlockKey;
import java.io.*;
import java.nio.ByteBuffer;
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
            var failureBytes = new ByteArrayOutputStream();
            new DataOutputStream(failureBytes).writeUTF(ledger.failure);
            byte[] failure = failureBytes.toByteArray();
            var graph = ledger.graph();
            var declarations = new HashMap<ConstructionDeclaration, byte[]>();
            long bodySize = 48L + failure.length + 12L + 12L * (ledger.cells.size() + ledger.destroyed.size());
            for (var declaration : ledger.cells.values()) bodySize += declarationBytes(declarations, declaration).length;
            for (var artifact : graph.records().values()) {
                bodySize += 16L + declarationBytes(declarations, artifact.declaration()).length
                        + 8L * artifact.parents().size() + 12L * artifact.cells().size();
            }
            if (bodySize > MAX_BYTES - 32) throw ConstructionLedger.invalid();
            // One final array: no geometric stream growth or full-body checksum copy.
            var out = ByteBuffer.allocate((int) bodySize + 32);
            out.putInt(MAGIC).putInt(VERSION);
            out.putLong(graph.namespace().getMostSignificantBits()).putLong(graph.namespace().getLeastSignificantBits());
            out.putLong(ledger.epoch).putLong(ledger.completedEpoch).putLong(graph.nextId());
            out.put(failure).putInt(ledger.cells.size());
            for (BlockKey pos : sorted(ledger.cells.keySet())) { pos(out, pos); out.put(declarations.get(ledger.cells.get(pos))); }
            out.putInt(ledger.destroyed.size()); for (BlockKey pos : sorted(ledger.destroyed)) pos(out, pos);
            out.putInt(graph.records().size());
            for (var artifact : graph.records().values()) {
                out.putLong(artifact.id()).put(declarations.get(artifact.declaration()));
                out.putInt(artifact.parents().size()); for (long parent : artifact.parents()) out.putLong(parent);
                out.putInt(artifact.cells().size()); for (BlockKey pos : artifact.cells()) pos(out, pos);
            }
            if (out.position() != bodySize) throw ConstructionLedger.invalid();
            out.put(hash(out.array(), out.position())); return out.array();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    private static byte[] declarationBytes(Map<ConstructionDeclaration, byte[]> cache, ConstructionDeclaration declaration) throws IOException {
        byte[] encoded = cache.get(declaration);
        if (encoded == null) {
            var bytes = new ByteArrayOutputStream();
            declaration(new DataOutputStream(bytes), declaration);
            encoded = bytes.toByteArray(); cache.put(declaration, encoded);
        }
        return encoded;
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
    private static void pos(ByteBuffer out, BlockKey p) { out.putInt(p.x()).putInt(p.y()).putInt(p.z()); }
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
    private static byte[] hash(byte[] data) { return hash(data, data.length); }
    private static byte[] hash(byte[] data, int length) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(data, 0, length); return digest.digest();
        }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
