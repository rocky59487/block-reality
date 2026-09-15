package com.blockreality.core.bsi;

import com.blockreality.api.BucklingState;
import java.util.List;

/** Complete immutable island records and their contract-defined world summary. */
public final class BsiBuckling {
    private BsiBuckling() { }
    private static final List<String> STATES = List.of("computed", "no-positive-eigenvalue",
            "not-eligible", "not-eligible-scale", "disabled-by-request", "solver-failed");
    private static final int[] RANK = {1, 0, 3, 2, -1, 4};

    public record Snapshot(BucklingState state, double factor, List<BsiResponse.Buckling> islands) {
        public Snapshot { islands = List.copyOf(islands); }
    }

    public static Snapshot decode(BsiResponse reply, int islandCount) {
        var header = reply.header().objField("buckling");
        String kind = header.str("kind", ""), token = header.str("state", "");
        require(kind.equals("none") || kind.equals("eigen"), "unsupported buckling kind");
        var rows = reply.buckling();
        require(islandCount >= 0 && rows.size() == islandCount, "incomplete buckling states");
        int state = kind.equals("none") ? 4 : rows.isEmpty() ? 2 : 1;
        double minimum = 0;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            require(row.island() == i, "invalid buckling island order/identity");
            require(row.state() >= 0 && row.state() < STATES.size(), "unknown buckling state");
            require(row.kind() == (kind.equals("none") ? 0 : 1)
                    && (kind.equals("none") == (row.state() == 4)), "invalid buckling kind/state");
            if (row.state() == 0) {
                require(Double.isFinite(row.factor()) && row.factor() > 0, "invalid buckling factor");
                minimum = minimum == 0 ? row.factor() : Math.min(minimum, row.factor());
            } else require(Double.isNaN(row.factor()), "non-computed buckling has factor");
            if (RANK[row.state()] > RANK[state]) state = row.state();
        }
        require(STATES.get(state).equals(token), "buckling header disagrees with islands");
        return new Snapshot(BucklingState.fromWire(token), state == 0 ? minimum : 0, rows);
    }

    private static void require(boolean ok, String why) {
        if (!ok) throw new IllegalArgumentException(why);
    }
}
