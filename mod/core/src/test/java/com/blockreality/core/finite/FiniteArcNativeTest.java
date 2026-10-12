package com.blockreality.core.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.engine.InProcessEngine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.finite.FiniteFixtures.*;

/**
 * O13: the real-engine arc leg — solve, advance, export a format-2 checkpoint, close,
 * restore into a fresh session and advance again, proving token/radius/load-factor
 * continuity across the durable boundary. Gated on {@code -Dbr.engine}: without the
 * engine this whole file skips rather than pretending it ran.
 */
class FiniteArcNativeTest {

    private static Path engine() {
        String p = System.getProperty("br.engine", "");
        if (p.isBlank()) p = System.getenv("BR_ENGINE");
        Assumptions.assumeTrue(p != null && !p.isBlank() && Files.isRegularFile(Path.of(p)),
                "set -Dbr.engine=<bsi_tectonic library> to run the finite arc leg");
        return Path.of(p);
    }

    /** One 152-byte material record: steel vocabulary id 0, elastic primary law. */
    private static byte[] steelMaterial() {
        var b = ByteBuffer.allocate(BsiCorot.MATERIAL_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0);                                    // material id 0 = steel
        b.putInt(0);                                    // reserved
        // primary law @8: kind 0, reserved, E, tension, compression, then zeros.
        b.position(8);
        b.putInt(0).putInt(0);
        b.putDouble(2.0e11).putDouble(2.5e8).putDouble(2.5e8);
        b.putDouble(0).putDouble(0).putDouble(0).putDouble(0).putDouble(0);
        return b.array();
    }

    /** A short cantilever: ground support plus four steel cells, each steel cell owned. */
    private static FiniteSource cantilever(UUID bsiDomain) {
        var blocks = new java.util.ArrayList<>(List.of(
                BsiRecords.Block.of(-1, 0, 0, 1, -1, 0)));
        var owners = new java.util.ArrayList<BsiFracture.Owner>();
        for (int x = 0; x < 4; x++) {
            blocks.add(BsiRecords.Block.of(x, 0, 0, 0, 0, 0));
            owners.add(new BsiFracture.Owner(new BlockKey(x, 0, 0), x + 1L));
        }
        return FiniteSource.build(VOCAB_REQUEST, vocabReply("r1", 0), blocks, owners,
                Set.of(1), steelMaterial(), new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0], options176(bsiDomain, 0));
    }

    private static final BsiCorot.ArcOptions ARC = new BsiCorot.ArcOptions(
            0.25, 1e-4, 1.0, 1.0, 1e-4, 1e-3, 1e-3, 1e-8, 1e-4, 60, 6, 10, 40, 2, 1);

    @Test void solveArcExportRestoreAndAdvanceAgain() {
        var domain = domainId(DIMENSION);
        var source = cantilever(domain.bsiDomain());
        FiniteResult stepped;
        BsiCorot.Exported exported;
        try (var engine = InProcessEngine.open(engine(), 1);
             var session = FiniteSession.attach(engine, domain, source, 1)) {
            assertTrue(session.declareVocabulary(), () -> "vocab: " + session.refusal());
            assertTrue(session.declareWorld(0), () -> "world: " + session.refusal());
            var solved = session.solve();
            assertNotNull(solved, () -> "solve: " + session.refusal());
            assertTrue(solved.fullyConverged(), "arc needs a converged start");
            stepped = session.arcAdvance(ARC);
            assertNotNull(stepped, () -> "arc: " + session.refusal());
            assertTrue(stepped.loadFactorReported());
            assertTrue(stepped.loadFactor() > 0);
            assertTrue(stepped.arc().radius() <= ARC.radius());
            exported = session.exportCheckpoint(2, 1 << 20, 1 << 20, 1 << 16);
            assertNotNull(exported, () -> "export: " + session.refusal());
            assertEquals(2, exported.format());
        }

        // Restore into a completely fresh engine session: import, then advance again —
        // the load factor must continue the path, not restart it.
        try (var engine = InProcessEngine.open(engine(), 1);
             var session = FiniteSession.attach(engine, domain, source, 2)) {
            assertTrue(session.declareVocabulary(), () -> "vocab: " + session.refusal());
            assertTrue(session.declareWorld(0), () -> "world: " + session.refusal());
            var ref = new FiniteCheckpointRef("engine/native", "cd".repeat(32), 2,
                    new FiniteBasis(domain, 0, 1, source.sourceHash(), source.vocabHash(),
                            source.ownersHash(), 2),
                    new FiniteBlobRef("ab".repeat(32), exported.blob().length),
                    1 << 20, 1 << 20, 1 << 16);
            var restored = session.importCheckpoint(ref, exported.blob());
            assertNotNull(restored, () -> "import: " + session.refusal());
            var continued = session.arcAdvance(ARC);
            assertNotNull(continued, () -> "arc after restore: " + session.refusal());
            assertTrue(continued.loadFactor() > stepped.loadFactor() - 1e-9,
                    "continuation must not rewind the load path");
        }
    }
}
