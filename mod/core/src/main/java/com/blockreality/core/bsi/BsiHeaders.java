package com.blockreality.core.bsi;

import com.blockreality.core.json.JsonWriter;

import java.util.List;

/**
 * Request headers, with their keys in the order the schema lists them.
 *
 * <p>The contract only requires that order on the way back (BSI B.5), and the host accepts any
 * order on the way in. Emitting it anyway costs nothing and makes a request comparable to the
 * schema by eye and by test — which is how a drift gets noticed before it becomes a defect.
 */
public final class BsiHeaders {

    private BsiHeaders() {}

    private static JsonWriter base(String id, String method, long revision) {
        return new JsonWriter().beginObj()
                .kv("bsi", BsiContract.MAJOR).kv("kind", "request")
                .kv("id", id).kv("method", method).kv("revision", revision);
    }

    /** {@code bsi.hello} — the handshake that decides whether the two sides speak the same contract. */
    public static String hello(String id, long revision, String client, String contractSha256, long arenaMaxBytes) {
        JsonWriter w = base(id, "bsi.hello", revision);
        w.key("body").beginObj()
                .kv("bsi", BsiContract.MAJOR).kv("client", client).kv("contractSha256", contractSha256)
                .key("arena").beginObj().kv("supported", arenaMaxBytes > 0).kv("maxBytes", arenaMaxBytes).endObj()
                .endObj();
        return w.endObj().done();
    }

    /** {@code bsi.vocab.declare} — the caller supplies the already-built body (materials and sections). */
    public static String vocabDeclare(String id, long revision, String bodyJson) {
        String head = base(id, "bsi.vocab.declare", revision).done();
        return head + ",\"body\":" + bodyJson + "}";
    }

    public static String worldDeclare(String id, long revision, int blocks, int attrs) {
        JsonWriter w = base(id, "bsi.world.declare", revision);
        w.key("body").beginObj().kv("blocks", blocks);
        if (attrs > 0) w.kv("attrs", attrs);
        w.endObj();
        return w.endObj().done();
    }

    /** Options of one solve. {@code include} names the optional sections; null means none. */
    public static String solve(String id, long revision, boolean selfWeight, double[] gravity,
                               int loads, Integer numThreads, List<String> include) {
        JsonWriter w = base(id, "bsi.solve", revision);
        w.key("body").beginObj().kv("selfWeight", selfWeight);
        if (gravity != null) {
            w.key("gravity").beginArr().val(gravity[0]).val(gravity[1]).val(gravity[2]).endArr();
        }
        if (loads > 0) w.kv("loads", loads);
        if (numThreads != null) w.kv("numThreads", numThreads.intValue());
        if (include != null && !include.isEmpty()) {
            w.key("include").beginArr();
            for (String s : include) w.val(s);
            w.endArr();
        }
        w.endObj();
        return w.endObj().done();
    }

    public static String cancel(String id, long revision, String targetId) {
        JsonWriter w = base(id, "bsi.cancel", revision);
        w.key("body").beginObj().kv("targetId", targetId).endObj();
        return w.endObj().done();
    }
}
