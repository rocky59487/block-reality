package com.blockreality.core.bsi;

import com.blockreality.api.*;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.json.JsonValue;
import javax.annotation.Nonnull;
import java.util.*;

/** One complete commit reply to the shared game API. Aggregates supplied data, never mechanics. */
public final class BsiAnalysisResult {
    private BsiAnalysisResult() { }
    public static final List<String> INCLUDE = List.of("members", "memberGeometry", "stations", "stationIdentity", "shells");
    private static final List<BucklingState> BUCKLING_STATES = List.of(BucklingState.COMPUTED,
            BucklingState.NO_POSITIVE_EIGENVALUE, BucklingState.NOT_ELIGIBLE,
            BucklingState.NOT_ELIGIBLE_SCALE, BucklingState.DISABLED_BY_REQUEST, BucklingState.SOLVER_FAILED);

    /** A rejected/stale/incomplete reply produces an empty failed result at the expected revision. */
    @Nonnull public static AnalysisResult decode(BsiResponse reply, @Nonnull WorldRevision expected,
            @Nonnull Map<Integer,String> materials, @Nonnull Map<Integer,String> sections,
            @Nonnull BsiHeaders.Precision requested) {
        try { return complete(reply, expected, materials, sections, requested); }
        catch (IllegalArgumentException e) { return AnalysisResult.failed(expected, "BSI analysis: " + e.getMessage()); }
    }

    private static AnalysisResult complete(BsiResponse reply, WorldRevision expected,
            Map<Integer,String> materials, Map<Integer,String> sections, BsiHeaders.Precision requested) {
        require(reply != null, "engine did not provide a reply");
        require(!reply.isError(), reply.code() + ": " + reply.message());
        var h = reply.header();
        require("response".equals(h.str("kind", "")) && "bsi.solve".equals(h.str("method", "")), "not a solve response");
        require(h.isExactInt("revision") && expected.value() >= 0 && reply.revision() == expected.value(), "wrong revision");
        require("ok".equals(reply.status()), "incomplete solve");
        require(requested != null && requested.tier() == BsiHeaders.Tier.COMMIT, "commit tier required");
        for (String name : List.of("blocks", "equilibrium", "quality", "buckling", "members", "memberBlocks",
                "memberGeometry", "stationIdentity", "facets", "facetBlocks"))
            require(reply.sections().containsKey(name), "missing " + name);
        String suffix = requested.storage() == BsiHeaders.Storage.F32 ? ":f32" : "";
        require(reply.sections().containsKey("stations" + suffix)
                && reply.sections().containsKey("facetSurfaces" + suffix), "missing requested recovery storage");
        var quality = reply.quality(); var eq = reply.equilibrium();
        require(quality != null && quality.tierHonoured() && !quality.timedOut(), "commit quality not honoured");
        require(quality.storage() == (requested.storage() == BsiHeaders.Storage.F32 ? 1 : 0), "storage mismatch");
        require(Double.isFinite(quality.achievedRel()) && quality.achievedRel() >= 0 && quality.iterations() >= 0, "invalid quality");
        require(eq != null && Double.isFinite(eq.residual()) && eq.residual() >= 0, "invalid equilibrium");
        for (double x : eq.applied()) require(Double.isFinite(x), "invalid applied force");
        for (double x : eq.reaction()) require(Double.isFinite(x), "invalid reaction");
        var diag = h.objField("diag");
        int islands = count(diag, "islands"), singular = count(diag, "singularIslands");
        require(singular <= islands, "singular count exceeds islands");
        var snapshot = BsiBuckling.decode(reply, islands);
        var blocks = reply.blocks();
        require(count(diag, "blocks") == blocks.size(), "block count mismatch");
        var beams = BsiBeamDisplay.decode(reply, expected.value(), materials, sections);
        var shells = BsiShellDisplay.decode(reply, expected.value(), materials);
        // diag counts extracted elements, including ones in singular islands; samples contain solved elements.
        require(beams.size() <= count(diag, "members") && shells.size() <= count(diag, "facets"), "element count mismatch");
        require(singular > 0 || (beams.size() == count(diag, "members") && shells.size() == count(diag, "facets")), "missing solved elements");
        Set<Integer> beamIds = new HashSet<>(), shellIds = new HashSet<>();
        for (var b : beams) require(beamIds.add(b.id()), "duplicate member id");
        for (var s : shells) require(shellIds.add(s.id()), "duplicate facet id");
        boolean overloaded = false, critical = false; double maxDc = 0;
        int governing = -1; String kind = "";
        for (var b : blocks) {
            require(Double.isFinite(b.dc()) && b.dc() >= 0 && (b.flags() & ~7) == 0, "invalid block verdict");
            require(b.ownerKind() >= 0 && b.ownerKind() <= 3 && b.mode() <= 6, "invalid block owner");
            require(b.island() >= -1 && b.island() < islands, "block island out of range");
            if (b.ownerKind() == 1 || b.ownerKind() == 2) {
                require(b.island() >= 0 && (b.ownerKind() == 1 ? beamIds : shellIds).contains(b.owner()), "missing block owner");
                if (governing < 0 || b.dc() > maxDc) {
                    maxDc = b.dc(); governing = b.owner(); kind = b.ownerKind() == 1 ? "member" : "shell";
                }
            } else require((b.flags() & 5) == 0, "unowned block has decision flags");
            overloaded |= b.overloaded(); critical |= b.bucklingCritical();
        }
        for (var b : blocks) if (b.bucklingCritical())
            require(snapshot.islands().get(b.island()).state() == 0, "critical flag without computed island");
        var unassigned = unassigned(h);
        long unassignedCount = unassigned.stream().mapToLong(g -> g.blocks().size()).sum();
        require(unassignedCount == blocks.stream().filter(BsiResponse.BlockResult::unassigned).count(), "unassigned count mismatch");
        return new AnalysisResult(expected, true, singular > 0, singular == 0 ? "" : singular + " mechanism island(s)",
                maxDc, governing, kind, islands, singular, eq.residual(), snapshot.factor(), snapshot.state(), beams, shells,
                unassigned, overloaded, critical, snapshot.islands().stream().map(row ->
                        new IslandBuckling(row.island(), row.kind() == 0 ? IslandBuckling.Kind.NONE : IslandBuckling.Kind.EIGEN,
                                BUCKLING_STATES.get(row.state()), row.factor())).toList());
    }

    private static List<UnassignedBlocks> unassigned(JsonValue h) {
        require(h.isArr("unassigned"), "missing unassigned groups");
        List<UnassignedBlocks> out = new ArrayList<>(); Set<BlockKey> seen = new HashSet<>();
        for (var g : h.arr("unassigned")) {
            require(g.isStr("why") && !g.str("why", "").isBlank() && g.isArr("blocks"), "invalid unassigned group");
            List<BlockKey> cells = new ArrayList<>();
            for (var c : g.arr("blocks")) {
                var xyz = c.asArr(); require(xyz.size() == 3, "invalid unassigned coordinate");
                int[] p = new int[3];
                for (int i = 0; i < 3; i++) {
                    double n = xyz.get(i).asNum(Double.NaN);
                    require(Double.isFinite(n) && n == Math.rint(n) && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE, "invalid unassigned coordinate");
                    p[i] = (int)n;
                }
                var cell = new BlockKey(p[0],p[1],p[2]); require(seen.add(cell), "duplicate unassigned block"); cells.add(cell);
            }
            out.add(UnassignedBlocks.of(g.str("why", ""), cells));
        }
        return List.copyOf(out);
    }
    private static int count(JsonValue h, String name) {
        require(h.isExactInt(name), "missing/inexact " + name);
        long n = h.exactI64(name); require(n >= 0 && n <= Integer.MAX_VALUE, "invalid " + name); return (int)n;
    }
    private static void require(boolean ok, String why) { if (!ok) throw new IllegalArgumentException(why); }
}
