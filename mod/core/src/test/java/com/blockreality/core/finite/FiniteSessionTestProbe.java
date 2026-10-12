package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.json.JsonValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * The scripted native-shaped peer shared by the finite session/preview/history tests:
 * each {@code request} pops one scripted answer, {@code declareFiniteWorld} speaks the
 * same identified world-declare wire the production {@code InProcessEngine} does.
 */
final class FiniteSessionTestProbe implements FiniteLane {

    record Call(String method, String header, byte[] payload) { }

    final List<Call> calls = new ArrayList<>();
    final Deque<Scripted> script = new ArrayDeque<>();
    /** The scripted engine's declared capabilities — the W4-A core set by default. */
    final java.util.Set<String> capabilities = new java.util.HashSet<>(List.of(
            "bsi.corot", "bsi.corot.fracture", "bsi.fracture",
            "bsi.corot.fracture.loads", "bsi.corot.shell.materials"));
    boolean vocabularyAdopted;
    boolean worldDeclared;
    int worldSequence;

    sealed interface Scripted { }
    record Reply(String header, byte[] payload) implements Scripted { }
    record Refuse(String code) implements Scripted { }

    void reply(String header) { script.add(new Reply(header, new byte[0])); }
    void reply(String header, byte[] payload) { script.add(new Reply(header, payload)); }
    void error(String code) { script.add(new Refuse(code)); }

    @Override public BsiResponse request(String headerJson, byte[] payload) {
        var header = JsonValue.parse(headerJson);
        calls.add(new Call(header.str("method", ""), headerJson, payload == null ? new byte[0] : payload));
        var next = script.poll();
        if (next == null) throw new AssertionError("unscripted native call: " + headerJson);
        if (next instanceof Refuse refused) {
            return respond("{\"bsi\":1,\"kind\":\"error\",\"id\":\"" + header.str("id", "")
                    + "\",\"method\":\"" + header.str("method", "") + "\",\"revision\":"
                    + header.exactI64("revision") + ",\"code\":\"" + refused.code()
                    + "\",\"message\":\"scripted refusal\"}", new byte[0]);
        }
        var reply = (Reply) next;
        return respond(reply.header(), reply.payload());
    }

    @Override public boolean adoptVocabulary(BsiVocabulary vocabulary) {
        vocabularyAdopted = true;
        return true;
    }

    @Override public boolean declareFiniteWorld(BsiFracture.World world) {
        String id = "w" + (++worldSequence);
        var response = request(BsiFracture.declare(id, world), world.payload());
        try {
            BsiFracture.checkWorld(response, id, world);
            worldDeclared = true;
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    @Override public boolean ready() { return true; }

    @Override public boolean has(String capability) { return capabilities.contains(capability); }

    static BsiResponse respond(String header, byte[] payload) {
        return BsiResponse.of(new BsiFrame.Decoded(0, header, payload));
    }

    /** A converged {@code analyzed} reply naming the given fresh token. */
    static String solveOk(String id, UUID domain, long revision, UUID ns,
                          BsiFracture.Token token, String generation, boolean converged) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id + "\",\"method\":\"bsi.corot.solve\","
                + "\"revision\":" + revision + ",\"status\":\"analyzed\","
                + "\"basis\":{\"domain\":\"" + BsiFracture.hex(domain) + "\",\"revision\":" + revision + "},"
                + "\"artifactNamespace\":\"" + BsiFracture.hex(ns) + "\","
                + "\"generation\":\"" + generation + "\","
                + "\"token\":{\"context\":\"" + BsiFracture.hex(token.context())
                + "\",\"sequence\":\"" + token.sequence() + "\"},\"fullyConverged\":" + converged
                + ",\"sections\":[]}";
    }

    /** The {@code restored} reply a checkpoint import answers with. */
    static String restored(String id, UUID domain, long revision, UUID ns,
                           BsiFracture.Token token, String generation) {
        return solveOk(id, domain, revision, ns, token, generation, true)
                .replace("\"method\":\"bsi.corot.solve\"", "\"method\":\"bsi.corot.checkpoint.import\"")
                .replace("\"status\":\"analyzed\"", "\"status\":\"restored\"");
    }
}
