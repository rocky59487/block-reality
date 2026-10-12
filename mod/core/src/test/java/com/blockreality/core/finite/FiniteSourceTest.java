package com.blockreality.core.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiRecords;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Finite source construction rules: canonical order, owner coverage, contract strides. */
class FiniteSourceTest {

    @Test void canonicalOrderIsInsenstiveToInputOrder() {
        var domain = FiniteFixtures.domainId(FiniteFixtures.DIMENSION);
        var a = FiniteFixtures.source(domain.bsiDomain(), 1);
        var b = FiniteSource.build(FiniteFixtures.VOCAB_REQUEST, FiniteFixtures.vocabReply("r9", 5),
                List.of(FiniteFixtures.STEEL, FiniteFixtures.GROUND),   // reversed input order
                List.of(new BsiFracture.Owner(new BlockKey(1, 64, 1), 1)),
                Set.of(1), new byte[152], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], FiniteFixtures.options176(domain.bsiDomain(), 1));
        // Only the stored vocab reply differs; the canonical wire sections must be identical.
        assertArrayEquals(a.blocks40(), b.blocks40());
        assertArrayEquals(a.owners20(), b.owners20());
        assertArrayEquals(a.corotPayload(), b.corotPayload());
    }

    @Test void supportCellsStayInBlocksButNeverOwnAnything() {
        var domain = FiniteFixtures.domainId(FiniteFixtures.DIMENSION);
        var source = FiniteFixtures.source(domain.bsiDomain(), 1);
        assertEquals(2, source.blockCount());
        assertEquals(1, source.ownerCount());
        assertEquals(2 * BsiRecords.BLOCK_BYTES + 20, source.worldPayload().length);
        // An owner pointing at the support cell is refused.
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build(
                FiniteFixtures.VOCAB_REQUEST, FiniteFixtures.vocabReply("r1", 0),
                List.of(FiniteFixtures.GROUND, FiniteFixtures.STEEL),
                List.of(new BsiFracture.Owner(new BlockKey(0, 63, 0), 1)),
                Set.of(1), new byte[152], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], FiniteFixtures.options176(domain.bsiDomain(), 1)));
        // A structural cell missing its owner is refused.
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build(
                FiniteFixtures.VOCAB_REQUEST, FiniteFixtures.vocabReply("r1", 0),
                List.of(FiniteFixtures.GROUND, FiniteFixtures.STEEL),
                List.of(), Set.of(1), new byte[152], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], FiniteFixtures.options176(domain.bsiDomain(), 1)));
        // An owner on a foreign cell is refused.
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build(
                FiniteFixtures.VOCAB_REQUEST, FiniteFixtures.vocabReply("r1", 0),
                List.of(FiniteFixtures.GROUND, FiniteFixtures.STEEL),
                List.of(new BsiFracture.Owner(new BlockKey(1, 64, 1), 1),
                        new BsiFracture.Owner(new BlockKey(7, 7, 7), 2)),
                Set.of(1), new byte[152], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], FiniteFixtures.options176(domain.bsiDomain(), 1)));
    }

    @Test void malformedInputsFailClosed() {
        var domain = FiniteFixtures.domainId(FiniteFixtures.DIMENSION);
        var options = FiniteFixtures.options176(domain.bsiDomain(), 1);
        var blocks = List.of(FiniteFixtures.GROUND, FiniteFixtures.STEEL);
        var owners = List.of(new BsiFracture.Owner(new BlockKey(1, 64, 1), 1));
        var materials = new byte[152];
        // Bad vocab request: unparseable / missing version.
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build("not json",
                FiniteFixtures.vocabReply("r1", 0), blocks, owners, Set.of(1), materials,
                new byte[0], new byte[0], new byte[0], new byte[0], new byte[0], options));
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build("{\"materials\":[]}",
                FiniteFixtures.vocabReply("r1", 0), blocks, owners, Set.of(1), materials,
                new byte[0], new byte[0], new byte[0], new byte[0], new byte[0], options));
        // Bad vocab reply: wrong method, missing tables.
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build(
                FiniteFixtures.VOCAB_REQUEST, "{\"bsi\":1}", blocks, owners, Set.of(1), materials,
                new byte[0], new byte[0], new byte[0], new byte[0], new byte[0], options));
        // Contract strides: 151B is not a material; 100B is not options.
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build(
                FiniteFixtures.VOCAB_REQUEST, FiniteFixtures.vocabReply("r1", 0), blocks, owners,
                Set.of(1), new byte[151], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], options));
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build(
                FiniteFixtures.VOCAB_REQUEST, FiniteFixtures.vocabReply("r1", 0), blocks, owners,
                Set.of(1), materials, new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], new byte[100]));
        // Shell laws and layers must arrive together (ABI10).
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build(
                FiniteFixtures.VOCAB_REQUEST, FiniteFixtures.vocabReply("r1", 0), blocks, owners,
                Set.of(1), materials, new byte[56], new byte[0],
                new byte[0], new byte[0], new byte[0], options));
        // Duplicate blocks are refused by the canonical encoder.
        assertThrows(IllegalArgumentException.class, () -> FiniteSource.build(
                FiniteFixtures.VOCAB_REQUEST, FiniteFixtures.vocabReply("r1", 0),
                List.of(FiniteFixtures.STEEL, FiniteFixtures.STEEL), owners,
                Set.of(1), materials, new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], options));
    }

    @Test void hashesBindDurableIdentity() {
        var domain = FiniteFixtures.domainId(FiniteFixtures.DIMENSION);
        var source = FiniteFixtures.source(domain.bsiDomain(), 2);
        assertEquals(64, source.sourceHash().length());
        assertEquals(64, source.vocabHash().length());
        assertEquals(64, source.ownersHash().length());
        var differentWorld = FiniteSource.build(FiniteFixtures.VOCAB_REQUEST, FiniteFixtures.vocabReply("r1", 0),
                List.of(FiniteFixtures.GROUND, FiniteFixtures.STEEL,
                        new BsiRecords.Block(2, 64, 1, 0, 0, 1, 0, 0, 1.0, 1.0)),
                List.of(new BsiFracture.Owner(new BlockKey(1, 64, 1), 1),
                        new BsiFracture.Owner(new BlockKey(2, 64, 1), 2)),
                Set.of(1), new byte[152], new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], FiniteFixtures.options176(domain.bsiDomain(), 2));
        assertNotEquals(source.sourceHash(), differentWorld.sourceHash());
        assertNotEquals(source.ownersHash(), differentWorld.ownersHash());
        // Two adjacent same-material structural cells keep separate owner ids — nothing merges.
        var owners = differentWorld.owners();
        assertEquals(2, owners.size());
        assertNotEquals(owners.get(0).artifact(), owners.get(1).artifact());
    }
}
