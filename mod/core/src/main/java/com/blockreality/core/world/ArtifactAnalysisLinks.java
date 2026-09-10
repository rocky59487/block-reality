package com.blockreality.core.world;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.WorldRevision;
import java.util.List;
import java.util.Optional;

/** Preview links come from native cell ownership; element numbers are valid only at this revision. */
public record ArtifactAnalysisLinks(WorldRevision revision, List<Integer> members, List<Integer> shells) {
    public ArtifactAnalysisLinks { members = List.copyOf(members); shells = List.copyOf(shells); }
    public static Optional<ArtifactAnalysisLinks> of(ConstructionLedger ledger, long object,
            AnalysisResult result, WorldRevision current) {
        var artifact = ledger.graph().records().get(object);
        if (!ledger.ready() || artifact == null || !artifact.active() || result == null || !result.ok()
                || !result.revision().equals(current)) return Optional.empty();
        var owners = ledger.graph().owners();
        return Optional.of(new ArtifactAnalysisLinks(current,
                result.members().stream().filter(m -> m.blocks().stream().anyMatch(p -> Long.valueOf(object).equals(owners.get(p))))
                        .map(m -> m.id()).sorted().toList(),
                result.shells().stream().filter(s -> s.blocks().stream().anyMatch(p -> Long.valueOf(object).equals(owners.get(p))))
                        .map(s -> s.id()).sorted().toList()));
    }
}
