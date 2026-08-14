package com.blockreality.api;

import com.blockreality.api.geom.BlockKey;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of one analysis, stamped with the revision it was computed for.
 *
 * <p>Three outcomes are distinguished, and conflating any two of them is a bug:
 *
 * <ul>
 *   <li><strong>Solved</strong> — {@code ok}, not {@code singular}: the numbers mean
 *       something.
 *   <li><strong>Mechanism</strong> — {@code ok} but {@code singular}: the structure is
 *       not restrained. There is no answer to report, and reporting zeros would be a
 *       lie. {@link #diagnostic} says so in words.
 *   <li><strong>Failed</strong> — {@code !ok}: the engine could not answer. The world
 *       must not change (API_ARCHITECTURE §2: analysis failure never mutates the world).
 * </ul>
 *
 * @param revision    the world revision this was computed for
 * @param ok          the engine produced an answer
 * @param singular    the stiffness matrix was singular: a mechanism, not a structure
 * @param diagnostic  human-readable explanation when {@code singular} or {@code !ok}
 * @param maxDc       largest demand/capacity across all members
 * @param governing   id of the member holding {@code maxDc}, or {@code -1}
 * @param unassigned  blocks that no member could be extracted from
 */
public record AnalysisResult(
        WorldRevision revision,
        boolean ok,
        boolean singular,
        String diagnostic,
        double maxDc,
        int governing,
        List<MemberSnapshot> members,
        List<BlockKey> unassigned) {

    public AnalysisResult {
        members = List.copyOf(members);
        unassigned = List.copyOf(unassigned);
        diagnostic = diagnostic == null ? "" : diagnostic;
    }

    /** True only when the numbers are meaningful — {@code ok} alone is not enough. */
    public boolean isUsable() { return ok && !singular; }

    public static AnalysisResult failed(WorldRevision rev, String why) {
        return new AnalysisResult(rev, false, false, why, 0, -1, List.of(), List.of());
    }

    public Optional<MemberSnapshot> member(int id) {
        return members.stream().filter(m -> m.id() == id).findFirst();
    }
}
