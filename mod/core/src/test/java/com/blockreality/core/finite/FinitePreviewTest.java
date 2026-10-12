package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.json.JsonValue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.finite.FiniteFixtures.*;

/**
 * The private preview handle (spec O6): analysis on a preview lane leaves the authority
 * session untouched, tokens are not interchangeable between handles, and closing the
 * preview cannot poison the authority.
 */
class FinitePreviewTest {

    /** A second scripted lane — the preview's private handle. */
    private static FiniteSessionTestProbe lane() { return new FiniteSessionTestProbe(); }

    private FiniteDomainId domain;
    private FiniteSource source;

    private void setup() {
        domain = domainId(DIMENSION);
        source = FiniteFixtures.source(domain.bsiDomain(), 4);
    }

    private void open(FiniteSessionTestProbe lane) {
        lane.reply(vocabReply("f1", 0));
        lane.reply(worldOk(4));
    }

    private static String worldOk(long revision) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"w1\",\"method\":\"bsi.world.declare\","
                + "\"revision\":" + revision + ",\"status\":\"ok\",\"identity\":{\"domain\":\""
                + BsiFracture.hex(domainId(DIMENSION).bsiDomain()) + "\",\"revision\":" + revision
                + ",\"artifactNamespace\":\"" + BsiFracture.hex(domainId(DIMENSION).artifactNamespace()) + "\"}}";
    }

    @Test void previewArcNeverWritesAuthorityAndTokensDoNotCross() {
        setup();
        var authorityLane = lane();
        open(authorityLane);
        var authority = FiniteSession.attach(authorityLane, domain, source, 11);
        assertTrue(authority.declareVocabulary());
        assertTrue(authority.declareWorld(4));
        authorityLane.reply(FiniteSessionTestProbe.solveOk("f2", domain.bsiDomain(), 4,
                domain.artifactNamespace(), CorotArcFixtures.TOKEN_A, "0123456789abcdef", true));
        var solved = authority.solve();
        assertNotNull(solved);

        // The preview replays durable state onto its own private lane and advances.
        var previewLane = lane();
        open(previewLane);
        var preview = FinitePreview.restore(previewLane, domain, source, 4, null, null, 77);
        assertNotNull(preview);
        previewLane.reply(FiniteSessionTestProbe.solveOk("f2", domain.bsiDomain(), 4,
                domain.artifactNamespace(), CorotArcFixtures.TOKEN_B, "fedcba9876543210", true));
        assertNotNull(preview.solve());
        previewLane.reply(CorotArcFixtures.arcReply("f3", 4, domain.bsiDomain(),
                        domain.artifactNamespace(), "aaaabbbbccccdddd", CorotArcFixtures.TOKEN_C,
                        1.25, 0.5, 1e-12),
                CorotArcFixtures.payload(domain.bsiDomain(), 4));
        var stepped = preview.arcAdvance(CorotArcFixtures.arcOptions());
        assertNotNull(stepped);
        assertEquals(77, stepped.basis().sessionGeneration());    // the preview generation, not 11

        // The authority saw nothing: its next arc still consumes its own live token.
        preview.close();
        authorityLane.reply(CorotArcFixtures.arcReply("f3", 4, domain.bsiDomain(),
                        domain.artifactNamespace(), "0000111122223333",
                        new BsiFracture.Token(new UUID(0xD4, 0xD4), "dddd4444dddd4444"),
                        1.5, 0.5, 0.0),
                CorotArcFixtures.payload(domain.bsiDomain(), 4));
        assertNotNull(authority.arcAdvance(CorotArcFixtures.arcOptions()));
        var body = JsonValue.parseStrict(authorityLane.calls.get(3).header()).objField("body");
        assertEquals(BsiFracture.hex(CorotArcFixtures.TOKEN_A.context()),
                body.objField("initialAnalysis").str("context", ""));
        // The private lane saw only its own four calls: vocab, world, solve, arc.
        assertEquals(4, previewLane.calls.size());
        assertEquals(4, authorityLane.calls.size());
    }

    @Test void previewFailureRefusesItsOwnSessionOnly() {
        setup();
        var authorityLane = lane();
        open(authorityLane);
        var authority = FiniteSession.attach(authorityLane, domain, source, 11);
        assertTrue(authority.declareVocabulary());
        assertTrue(authority.declareWorld(4));
        authorityLane.reply(FiniteSessionTestProbe.solveOk("f2", domain.bsiDomain(), 4,
                domain.artifactNamespace(), CorotArcFixtures.TOKEN_A, "0123456789abcdef", true));
        assertNotNull(authority.solve());

        // The preview's own lane dies mid-arc: the authority is undisturbed.
        var previewLane = lane();
        open(previewLane);
        var preview = FinitePreview.restore(previewLane, domain, source, 4, null, null, 77);
        assertNotNull(preview);
        previewLane.reply(FiniteSessionTestProbe.solveOk("f2", domain.bsiDomain(), 4,
                domain.artifactNamespace(), CorotArcFixtures.TOKEN_B, "fedcba9876543210", true));
        assertNotNull(preview.solve());
        previewLane.error("ENGINE_INTERNAL");
        assertNull(preview.arcAdvance(CorotArcFixtures.arcOptions()));
        assertEquals(FiniteSession.Step.DISABLED, preview.step());

        authorityLane.reply(CorotArcFixtures.arcReply("f3", 4, domain.bsiDomain(),
                        domain.artifactNamespace(), "0000111122223333",
                        new BsiFracture.Token(new UUID(0xD4, 0xD4), "dddd4444dddd4444"),
                        1.5, 0.5, 0.0),
                CorotArcFixtures.payload(domain.bsiDomain(), 4));
        assertNotNull(authority.arcAdvance(CorotArcFixtures.arcOptions()),
                "a dead preview must never disturb the authority lane");
    }

    @Test void restoreRejectsAStaleBasisBeforeAnyAnalysis() {
        setup();
        var previewLane = lane();
        open(previewLane);
        // A checkpoint ref whose basis names a different world revision refuses at the
        // session boundary — no import request reaches the wire.
        var foreign = new FiniteCheckpointRef("engine/1.5", "cd".repeat(32), 1,
                new FiniteBasis(domain, 9, 0, source.sourceHash(), source.vocabHash(),
                        source.ownersHash(), 0),
                new FiniteBlobRef("ab".repeat(32), 64), 1 << 20, 4096, 4096);
        assertNull(FinitePreview.restore(previewLane, domain, source, 4, foreign, new byte[64], 77));
        assertEquals(2, previewLane.calls.size());                // vocab + world only
    }
}
