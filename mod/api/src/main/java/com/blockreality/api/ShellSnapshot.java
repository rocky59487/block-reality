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
 *                 independently, so an id alone does not identify an
 *                 element — see {@link AnalysisResult#governingKind()}.
 * @param plate    legacy plate token; BSI panels use their material token
 * @param dc       demand/capacity from the surface von Mises screen
 * @param dcRaw    legacy screen before support-moment recovery; NaN when unavailable in BSI. Kept so the
 *                 difference is visible rather than asserted: corner-sampled MITC4
 *                 moments are badly low at a clamped edge, and the recovery is the only
 *                 thing standing between that and an under-reported support.
 * @param edgeRecovered whether the recovery actually fired on this facet
 * @param blocks   engine-reported source cells; the count is independent of the four mesh corners
 * @param display  recovered samples shared by the overlay, picker, HUD and network
 * @param overloaded engine verdict, independent of the display number
 * @param governingFibre BSI governing-fibre token (0..6)
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
        Optional<ShellDisplayField> display,
        boolean overloaded,
        int governingFibre) {

    public ShellSnapshot {
        blocks = List.copyOf(blocks);
    }

    /** BSI has no pre-edge-recovery screen. NaN here is absence, not a zero measurement. */
    public java.util.OptionalDouble rawDc() {
        return Double.isNaN(dcRaw) ? java.util.OptionalDouble.empty() : java.util.OptionalDouble.of(dcRaw);
    }

    /** Peak signed-principal magnitude on this facet, or zero when there is no field. */
    public double peakMpa() {
        return display.map(ShellDisplayField::peakMagnitudeMpa).orElse(0.0);
    }
}
