package com.blockreality.api;

import com.blockreality.api.geom.BlockKey;

import java.util.List;
import java.util.Optional;

/**
 * One MITC4 plate facet as the engine sees it, for one analysis.
 *
 * <p>Snapshot in the same literal sense as {@link MemberSnapshot}: recomputed and thrown
 * away every revision, never an identity (D-011).
 *
 * @param id       engine-side facet index for this analysis. Facets and members number
 *                 from 1 <em>independently</em>, so an id alone does not identify an
 *                 element — see {@link AnalysisResult#governingKind()}.
 * @param plate    plate token, e.g. {@code concrete_slab_200}
 * @param dc       demand/capacity from the surface von Mises screen
 * @param dcRaw    the same screen <em>before</em> support-moment recovery. Kept so the
 *                 difference is visible rather than asserted: corner-sampled MITC4
 *                 moments are badly low at a clamped edge, and the recovery is the only
 *                 thing standing between that and an under-reported support.
 * @param edgeRecovered whether the recovery actually fired on this facet
 * @param blocks   the four plate blocks whose centres are this facet's corners
 */
public record ShellSnapshot(
        int id,
        String material,
        String plate,
        double thicknessMm,
        double dc,
        double dcRaw,
        boolean governingTopFace,
        boolean edgeRecovered,
        List<BlockKey> blocks,
        Optional<ShellFieldSpec> field) {

    public ShellSnapshot {
        blocks = List.copyOf(blocks);
    }

    /** Peak signed-principal magnitude on this facet, or zero when there is no field. */
    public double peakMpa() {
        return field.map(ShellFieldSpec::peakMagnitudeMpa).orElse(0.0);
    }
}
