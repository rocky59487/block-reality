package com.blockreality.core.finite;

import com.blockreality.core.finite.FiniteCodec.Operation;
import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.api.geom.BlockKey;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** BRV2OBJ1 / BRV2SRC1 / BRV2VAL1 / HEAD codec round-trips and fail-closed rejections. */
class FiniteCodecTest {

    @Test void objectEnvelopeRoundTripAndNameBinding() {
        var payload = new byte[]{1, 2, 3, 4, 5};
        var envelope = FiniteCodec.pack(FiniteCodec.KIND_SOURCE, payload);
        assertEquals(FiniteCodec.ENVELOPE_HEADER_BYTES + 5, envelope.length);
        var name = FiniteSource.sha256(envelope);
        var body = FiniteCodec.unpack(envelope, name);
        assertEquals(FiniteCodec.KIND_SOURCE, body.kind());
        assertArrayEquals(payload, body.payload());
        // A foreign name must never validate the same bytes.
        assertThrows(IllegalArgumentException.class, () -> FiniteCodec.unpack(envelope, "0".repeat(64)));
    }

    @Test void envelopeRejectsEveryField() {
        var envelope = FiniteCodec.pack(FiniteCodec.KIND_CHECKPOINT, new byte[]{9, 9, 9});
        var name = FiniteSource.sha256(envelope);
        var mutations = List.of(0, 7, 9, 13, 20, 30, 55);
        for (int offset : mutations) {
            var bad = envelope.clone();
            bad[offset] ^= 0x5A;
            assertThrows(IllegalArgumentException.class,
                    () -> FiniteCodec.unpack(bad, name), "offset " + offset);
        }
        var truncated = java.util.Arrays.copyOf(envelope, envelope.length - 1);
        assertThrows(IllegalArgumentException.class, () -> FiniteCodec.unpack(truncated, name));
        assertThrows(IllegalArgumentException.class, () -> FiniteCodec.pack(99, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> FiniteCodec.pack(FiniteCodec.KIND_SOURCE, new byte[0]));
    }

    @Test void sourceRoundTripPreservesEverySection() {
        var domain = FiniteFixtures.domainId(FiniteFixtures.DIMENSION);
        var source = FiniteFixtures.source(domain.bsiDomain(), 3);
        var serialized = source.serialize();
        var decoded = FiniteSource.decode(serialized);
        assertEquals(source, decoded);
        assertEquals(source.sourceHash(), decoded.sourceHash());
        assertArrayEquals(source.blocks40(), decoded.blocks40());
        assertArrayEquals(source.owners20(), decoded.owners20());
        assertArrayEquals(source.options(), decoded.options());
        assertArrayEquals(source.supportMaterialIds(), decoded.supportMaterialIds());
        // Structural tampering fails closed; payload bytes inside sections are guarded by the
        // object-envelope hash, so the codec proof targets the fields it actually validates.
        var badTag = serialized.clone();
        badTag[16] ^= 0x40;                          // first section tag
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.decode(badTag));
        var badLength = serialized.clone();
        badLength[20] ^= 1;                          // first section length (u64 low byte)
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.decode(badLength));
        var truncated = java.util.Arrays.copyOf(serialized, serialized.length - 1);
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.decode(truncated));
        var wrongMagic = serialized.clone();
        wrongMagic[3] = '9';
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.decode(wrongMagic));
        var wrongSchema = serialized.clone();
        wrongSchema[8] = 2;
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.decode(wrongSchema));
    }

    @Test void journalValuesRoundTrip() {
        var domain = FiniteDomain.create(FiniteFixtures.domainId(FiniteFixtures.DIMENSION), new UUID(30, 30));
        var decoded = FiniteCodec.decodeDomain(FiniteCodec.encodeDomain(domain));
        assertEquals(domain, decoded);
        var allocated = domain.allocated(4).observed();
        assertEquals(allocated, FiniteCodec.decodeDomain(FiniteCodec.encodeDomain(allocated)));

        var binding = ArtifactBinding.manufactured(7, new UUID(40, 40), new UUID(41, 41),
                FiniteFixtures.declaration(), List.of(new BlockKey(1, 64, 1), new BlockKey(3, 65, 2)));
        assertEquals(binding, FiniteCodec.decodeBinding(FiniteCodec.encodeBinding(binding)));
        var retired = binding.retired(new UUID(42, 42));
        assertEquals(retired, FiniteCodec.decodeBinding(FiniteCodec.encodeBinding(retired)));
        var legacy = ArtifactBinding.legacy(9, "old/observed/key", new UUID(43, 43),
                FiniteFixtures.declaration(), List.of(new BlockKey(9, 70, 9)));
        assertEquals(legacy, FiniteCodec.decodeBinding(FiniteCodec.encodeBinding(legacy)));

        var source = FiniteFixtures.source(domain.id().bsiDomain(), 0);
        var sourceRef = new FiniteBlobRef(FiniteSource.sha256(FiniteCodec.pack(FiniteCodec.KIND_SOURCE, source.serialize())),
                FiniteCodec.pack(FiniteCodec.KIND_SOURCE, source.serialize()).length);
        var root = new FiniteRoot(0, 0, sourceRef, null, FiniteRoot.GENESIS_HASH, new UUID(44, 44));
        assertEquals(root, FiniteCodec.decodeRoot(FiniteCodec.encodeRoot(root)));
        var withCheckpoint = new FiniteRoot(1, 1, sourceRef,
                new FiniteRoot.Checkpoint(sourceRef, 2, "engine/1.5", "ab".repeat(32), 1 << 20, 4096, 4096),
                FiniteSource.sha256(FiniteCodec.encodeRoot(root)), new UUID(45, 45));
        assertEquals(withCheckpoint, FiniteCodec.decodeRoot(FiniteCodec.encodeRoot(withCheckpoint)));
    }

    @Test void operationDescriptorsRoundTripAndUnknownTagsRefuse() {
        var ops = List.of(
                (Operation) new Operation.CreateDomain(FiniteFixtures.DIMENSION),
                new Operation.Bind(List.of(
                        Operation.BindingPlan.manufactured(new UUID(50, 50), FiniteFixtures.declaration(),
                                List.of(new BlockKey(1, 64, 1))),
                        Operation.BindingPlan.legacy("legacy/obs", FiniteFixtures.declaration(),
                                List.of(new BlockKey(5, 64, 5))))),
                new Operation.Retire(Set.of(1L, 3L, 7L)),
                new Operation.Observe(),
                new Operation.CommitRoot(new FiniteRoot.Next(0, 0,
                        new FiniteBlobRef("ab".repeat(32), 100), null)));
        for (var op : ops) {
            var encoded = FiniteCodec.encodeOperation(op);
            var decoded = FiniteCodec.decodeOperation(encoded);
            assertEquals(FiniteCodec.encodeOperation(decoded).length, encoded.length);
            var tagBad = encoded.clone();
            tagBad[12] = 9;                // unknown operation tag
            assertThrows(IllegalArgumentException.class, () -> FiniteCodec.decodeOperation(tagBad));
            var truncated = java.util.Arrays.copyOf(encoded, encoded.length - 1);
            assertThrows(IllegalArgumentException.class, () -> FiniteCodec.decodeOperation(truncated));
        }
        var unknown = FiniteCodec.encodeOperation(ops.get(0));
        unknown[8] = 77; // value kind byte
        assertThrows(IllegalArgumentException.class, () -> FiniteCodec.decodeOperation(unknown));
    }

    @Test void fracturePlanRoundTripWithNonEmptyLedgers() {
        var domain = FiniteFixtures.domainId(FiniteFixtures.DIMENSION);
        var source = FiniteFixtures.source(domain.bsiDomain(), 0);
        var sourceRef = new FiniteBlobRef(
                FiniteSource.sha256(FiniteCodec.pack(FiniteCodec.KIND_SOURCE, source.serialize())),
                FiniteCodec.pack(FiniteCodec.KIND_SOURCE, source.serialize()).length);
        var checkpoint = new FiniteRoot.Checkpoint(sourceRef, 2, "engine/2.0",
                "cd".repeat(32), 1 << 20, 16 << 20, 1 << 20);
        var loadLedger = new byte[BsiCorotFracture.FRACTURE_LOAD_BYTES];
        var archiveRecords = new byte[BsiCorotFracture.ARCHIVE_BYTES];
        for (int i = 0; i < loadLedger.length; i++) loadLedger[i] = (byte) (i + 1);
        for (int i = 0; i < archiveRecords.length; i++) archiveRecords[i] = (byte) (i + 3);
        var plan = new FiniteFracturePlan(domain, new UUID(60, 60), new UUID(61, 61),
                "test/actor",
                new FiniteFracturePlan.Basis(0, 0, "a".repeat(64), "b".repeat(64), "c".repeat(64)),
                new FiniteFracturePlan.After(1, 1, "d".repeat(64), sourceRef, checkpoint),
                "e".repeat(64), "f".repeat(64),
                new BsiFracture.Token(new UUID(0xF1, 0xF1), "f00df00df00df00d"),
                3, 1, 4, 0, 0, -1, true, "aabbccddeeff0011", 4,
                List.of(2L, 9L),
                List.of(new BsiFracture.Owner(new BlockKey(5, 70, 5), 42)),
                loadLedger, archiveRecords);
        var decoded = FiniteFracturePlan.decode(plan.encode());
        assertEquals(plan.planHash(), decoded.planHash());
        assertArrayEquals(loadLedger, decoded.loadLedger());
        assertArrayEquals(archiveRecords, decoded.archiveRecords());
        assertEquals(plan.retiredOwnership(), decoded.retiredOwnership());
        assertEquals(plan.bornBodies(), decoded.bornBodies());
    }

    @Test void headCacheRoundTripAndForeignDomainRefusal() {
        var domain = FiniteFixtures.domainId(FiniteFixtures.DIMENSION);
        var basis = new FiniteBasis(domain, 4, 7, "a".repeat(64), "b".repeat(64), "c".repeat(64), 12);
        var encoded = FiniteCodec.encodeHead(basis, "d".repeat(64));
        var head = FiniteCodec.decodeHead(encoded, domain);
        assertEquals("d".repeat(64), head.rootHash());
        assertEquals(basis, head.basis());
        var otherDomain = new FiniteDomainId(FiniteFixtures.JOURNAL, FiniteFixtures.DIMENSION,
                new UUID(99, 99), domain.artifactNamespace());
        assertThrows(IllegalArgumentException.class, () -> FiniteCodec.decodeHead(encoded, otherDomain));
        var bad = encoded.clone();
        bad[10] = 'x';
        assertThrows(IllegalArgumentException.class, () -> FiniteCodec.decodeHead(bad, domain));
    }
}
