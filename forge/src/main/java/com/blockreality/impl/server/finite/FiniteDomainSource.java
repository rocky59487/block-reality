package com.blockreality.impl.server.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.engine.GameWorldSnapshot;
import com.blockreality.core.engine.GameVocabulary;
import com.blockreality.core.finite.ArtifactBinding;
import com.blockreality.core.finite.FiniteDomainId;
import com.blockreality.core.finite.FiniteSource;
import com.blockreality.core.json.JsonValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Assembles the durable {@link FiniteSource} for one finite domain (design §3, §6):
 * adopted cells from the registry's active {@link ArtifactBinding}s, observed support
 * cells, the durable vocabulary pair, and the contract-sized record groups.
 *
 * <p>This is wire assembly only — ids are resolved through the durable vocabulary and
 * every record group arrives already sized by the caller (materials 152B, shell law 56B,
 * shell layer 16B, cell loads 64B, prescribed 64B, generalized loads 144B, options 176B).
 * Nothing here computes stress, force, damage or verdicts.
 *
 * <p>Rules enforced beyond {@link FiniteSource}'s own coverage check:
 *
 * <ul>
 *   <li>supports must face-touch an owned structural cell and never overlap one
 *       (the same adjacency contract the gather enforces);
 *   <li>a bound declaration must carry a wire axis (manufactured pieces always do);
 *   <li>the options stamp is re-derived from ({@code domain.bsiDomain},
 *       {@code worldRevision}) — a caller cannot mis-stamp the expected world;
 *   <li>{@code retainLoadPath} is always set — material-law records require it and the
 *       design never wants a non-retained solve.
 * </ul>
 */
public final class FiniteDomainSource {

    private FiniteDomainSource() { }

    /**
     * The caller-supplied record groups and vocabulary pair. {@code materials} must carry
     * at least one 152-byte law record (the source contract requires it); the vocabulary
     * reply must be the engine's durable {@code bsi.vocab.declare} answer.
     */
    public record Spec(String vocabRequestJson, String vocabReplyJson, String supportMaterial,
                       byte[] materials, byte[] shellLaws, byte[] shellLayers,
                       byte[] cellLoads, byte[] prescribed, byte[] loads,
                       BsiCorot.Options options) {
        public Spec {
            Objects.requireNonNull(vocabRequestJson);
            Objects.requireNonNull(vocabReplyJson);
            Objects.requireNonNull(supportMaterial);
            Objects.requireNonNull(options);
            materials = materials == null ? new byte[0] : materials.clone();
            shellLaws = shellLaws == null ? new byte[0] : shellLaws.clone();
            shellLayers = shellLayers == null ? new byte[0] : shellLayers.clone();
            cellLoads = cellLoads == null ? new byte[0] : cellLoads.clone();
            prescribed = prescribed == null ? new byte[0] : prescribed.clone();
            loads = loads == null ? new byte[0] : loads.clone();
            options = copy(options);
        }
        private static BsiCorot.Options copy(BsiCorot.Options o) {
            return new BsiCorot.Options(o.expectedDomain(), o.expectedRevision(), o.loadFactor(),
                    o.gravity().clone(), o.flags(), o.relativeTolerance(), o.forceTolerance(),
                    o.momentTolerance(), o.initialStep(), o.minStep(), o.maxStep(),
                    o.linearTolerance(), o.pathTolerance(), o.budgetDof(), o.numThreads(),
                    o.maxIterations(), o.maxAttempts(), o.maxBacktracks(), o.linearIterations(),
                    o.restart(), o.pointBudget(), o.fiberCells(), o.radialCells(), o.angularCells());
        }
    }

    /**
     * Build one complete source for {@code domain} at {@code worldRevision}. The blocks and
     * owners derive only from the committed {@code activeBindings}; {@code supports} are the
     * observed ground contacts and never enter the owners table.
     */
    public static FiniteSource build(FiniteDomainId domain, List<ArtifactBinding> activeBindings,
                                     Set<BlockKey> supports, Spec spec, long worldRevision) {
        Objects.requireNonNull(domain);
        Objects.requireNonNull(activeBindings);
        Objects.requireNonNull(supports);
        int version = (int) JsonValue.parseStrict(spec.vocabRequestJson()).exactI64("version");
        BsiVocabulary vocabulary =
                BsiVocabulary.decodeStored(JsonValue.parseStrict(spec.vocabReplyJson()), version);
        int supportId = vocabulary.materialId(spec.supportMaterial());

        var blocks = new ArrayList<BsiRecords.Block>();
        var owners = new ArrayList<BsiFracture.Owner>();
        var owned = new HashSet<BlockKey>();
        for (ArtifactBinding binding : activeBindings) {
            if (!binding.active()) continue;
            var declaration = binding.declaration();
            var product = GameVocabulary.binding(declaration.material(), declaration.section());
            int mat = product.materialId(vocabulary);
            int sect = product.sectionId(vocabulary);
            int axis = declaration.axis();
            if (axis < 0 || axis > 2)
                throw new IllegalArgumentException("finite binding carries no wire axis: " + binding.durableId());
            for (BlockKey cell : binding.cells()) {
                if (!owned.add(cell))
                    throw new IllegalArgumentException("finite bindings share a cell " + cell);
                blocks.add(new BsiRecords.Block(cell.x(), cell.y(), cell.z(), mat, sect, axis, 0, 0, 1.0, 1.0));
                owners.add(new BsiFracture.Owner(cell, binding.ownerId()));
            }
        }
        for (BlockKey support : supports) {
            if (owned.contains(support))
                throw new IllegalArgumentException("support cell overlaps an owned cell " + support);
            boolean adjacent = false;
            for (BlockKey neighbour : GameWorldSnapshot.neighbours(support))
                if (owned.contains(neighbour)) { adjacent = true; break; }
            if (!adjacent)
                throw new IllegalArgumentException("support cell is not face-adjacent to the structure " + support);
            blocks.add(BsiRecords.Block.of(support.x(), support.y(), support.z(), supportId, -1, 0));
        }
        byte[] options = options176(domain.bsiDomain(), worldRevision, spec.options());
        return FiniteSource.build(spec.vocabRequestJson(), spec.vocabReplyJson(), blocks, owners,
                Set.of(supportId), spec.materials(), spec.shellLaws(), spec.shellLayers(),
                spec.cellLoads(), spec.prescribed(), spec.loads(), options);
    }

    /**
     * The 176-byte {@code bsi_corot_options} record: the expected world stamp is always
     * re-derived from the domain and revision, and {@code retainLoadPath} is forced on —
     * material-law records and the honest-history contract both require it.
     */
    public static byte[] options176(java.util.UUID domain, long worldRevision, BsiCorot.Options template) {
        var b = ByteBuffer.allocate(FiniteSource.OPTIONS_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(FiniteSource.OPTIONS_BYTES);
        b.putInt(template.flags() | 4);                        // retainLoadPath is mandatory here
        b.putLong(domain.getMostSignificantBits()).putLong(domain.getLeastSignificantBits());
        b.putLong(worldRevision);
        b.putDouble(template.loadFactor());
        b.putDouble(template.gravity()[0]).putDouble(template.gravity()[1]).putDouble(template.gravity()[2]);
        b.putDouble(template.relativeTolerance()).putDouble(template.forceTolerance());
        b.putDouble(template.momentTolerance());
        b.putDouble(template.initialStep()).putDouble(template.minStep()).putDouble(template.maxStep());
        b.putDouble(template.linearTolerance()).putDouble(template.pathTolerance());
        b.putInt(template.budgetDof()).putInt(template.numThreads());
        b.putInt(template.maxIterations()).putInt(template.maxAttempts()).putInt(template.maxBacktracks());
        b.putInt(template.linearIterations()).putInt(template.restart());
        b.putInt(template.pointBudget()).putInt(template.fiberCells())
                .putInt(template.radialCells()).putInt(template.angularCells());
        b.putInt(0);                                           // trailing pad
        byte[] encoded = b.array();
        BsiCorot.options(encoded);                             // self-check through the strict decoder
        return encoded;
    }
}
