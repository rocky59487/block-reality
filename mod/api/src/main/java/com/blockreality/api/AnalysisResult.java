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
 *   <li><strong>Solved</strong> — {@code ok}, nothing singular: the numbers mean
 *       something.
 *   <li><strong>Mechanism</strong> — {@code ok} but singular: a structure is not
 *       restrained. There is no answer to report <em>for that structure</em>, and
 *       reporting zeros would be a lie. {@link #diagnostic} says so in words.
 *   <li><strong>Failed</strong> — {@code !ok}: the engine could not answer. The world
 *       must not change (API_ARCHITECTURE §2: analysis failure never mutates the world).
 * </ul>
 *
 * <h2>A world holds many structures</h2>
 * The engine partitions what it is given into connected components and solves each one on
 * its own, so {@code singular} means <strong>at least one</strong> structure is a
 * mechanism — never that this whole reply is worthless. {@link #islands} and
 * {@link #singularIslands} say how many of each, and the members and facets of the sound
 * structures are in this same result. {@link #isUsable()} is written accordingly: a
 * client that blanked its overlay on {@code singular} would throw away good answers,
 * which is exactly what happened when one unsupported beam anywhere in the world made
 * every building report nothing.
 *
 * @param revision    the world revision this was computed for
 * @param ok          the engine produced an answer
 * @param singular    at least one connected structure was a mechanism
 * @param diagnostic  human-readable explanation when singular or {@code !ok}
 * @param maxDc       largest demand/capacity across every member and facet
 * @param governing   id of the element holding {@code maxDc}, or {@code -1}
 * @param governingKind {@code "member"}, {@code "shell"}, or empty — members and facets
 *                      number independently, so the id alone is ambiguous
 * @param islands     how many separate structures were found and solved
 * @param singularIslands how many of those were mechanisms
 * @param equilibriumResidual  the engine's own force-balance check on this solve,
 *                             relative: applied load recomputed from geometry against the
 *                             sum of reactions. Machine precision on a sound solve.
 * @param bucklingFactor  smallest linear-buckling load factor across every structure:
 *                        the multiple of the CURRENT load at which something goes
 *                        unstable, so {@code <= 1} means it already has. 0 means it was
 *                        not computed. Deliberately not called a safety factor — it is
 *                        the eigenvalue of the linear onset problem and therefore an
 *                        upper bound on the real critical load, not a margin.
 * @param unassigned  blocks no element could be extracted from
 */
public record AnalysisResult(
        WorldRevision revision,
        boolean ok,
        boolean singular,
        String diagnostic,
        double maxDc,
        int governing,
        String governingKind,
        int islands,
        int singularIslands,
        double equilibriumResidual,
        double bucklingFactor,
        List<MemberSnapshot> members,
        List<ShellSnapshot> shells,
        List<BlockKey> unassigned) {

    public AnalysisResult {
        members = List.copyOf(members);
        shells = List.copyOf(shells);
        unassigned = List.copyOf(unassigned);
        diagnostic = diagnostic == null ? "" : diagnostic;
        governingKind = governingKind == null ? "" : governingKind;
    }

    /**
     * True when there is something meaningful to show.
     *
     * <p>Deliberately not {@code ok && !singular}: with one mechanism in the world and ten
     * sound buildings, there are ten sets of numbers worth drawing.
     */
    public boolean isUsable() { return ok && (!members.isEmpty() || !shells.isEmpty()); }

    /** True when nothing at all could be solved — every structure found was a mechanism. */
    public boolean allSingular() { return ok && singular && !isUsable(); }

    /**
     * True when some structure is at or past its linear buckling load.
     *
     * <p>Separate from {@code maxDc > 1} on purpose. They are different failures with
     * different causes: one is the material running out of strength, the other is the
     * geometry running out of stiffness, and a slender column reaches the second at a
     * stress the first calls comfortable.
     */
    public boolean bucklingCritical() { return bucklingFactor > 0 && bucklingFactor <= 1.0; }

    public static AnalysisResult failed(WorldRevision rev, String why) {
        return new AnalysisResult(rev, false, false, why, 0, -1, "", 0, 0, 0, 0,
                List.of(), List.of(), List.of());
    }

    public Optional<MemberSnapshot> member(int id) {
        return members.stream().filter(m -> m.id() == id).findFirst();
    }

    public Optional<ShellSnapshot> shell(int id) {
        return shells.stream().filter(s -> s.id() == id).findFirst();
    }
}
