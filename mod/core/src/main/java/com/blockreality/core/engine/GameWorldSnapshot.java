package com.blockreality.core.engine;

import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.world.ConstructionLedger;
import com.blockreality.core.world.ConstructionDeclaration;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** An immutable observed world, without inferred axes, model topology or physical verdicts. */
public record GameWorldSnapshot(WorldRevision revision, List<Cell> cells,
                                List<BlockKey> ground, List<BsiRecords.Load> loads) {
    private static final Comparator<BlockKey> ORDER = Comparator.comparingInt(BlockKey::x)
            .thenComparingInt(BlockKey::y).thenComparingInt(BlockKey::z);

    public record Cell(BlockKey pos, String material, String section, int axis, int joint,
                       int axisRot, double fill, double strength) {
        public Cell {
            Objects.requireNonNull(pos);
            GameVocabulary.binding(material, section);
            // Reuse the wire range validator; these dummy IDs are never sent or interpreted.
            new BsiRecords.Block(pos.x(), pos.y(), pos.z(), 0, -1, axis, joint, axisRot, fill, strength);
        }
        public static Cell of(BlockKey pos, String material, String section, int axis) {
            return new Cell(pos, material, section, axis, 0, 0, 1, 1);
        }
    }

    public GameWorldSnapshot {
        Objects.requireNonNull(revision);
        if (revision.value() < 0) throw new IllegalArgumentException("negative world revision");
        cells = cells.stream().sorted(Comparator.comparing(Cell::pos, ORDER)).toList();
        var occupied = new HashSet<BlockKey>();
        var adjacent = new HashSet<BlockKey>();
        for (var cell : cells) {
            if (!occupied.add(cell.pos())) throw new IllegalArgumentException("duplicate structural cell " + cell.pos());
            adjacent.addAll(neighbours(cell.pos()));
        }
        var uniqueGround = new HashSet<BlockKey>();
        for (var pos : ground) {
            if (occupied.contains(pos)) throw new IllegalArgumentException("ground overlaps structure " + pos);
            if (!adjacent.contains(pos)) throw new IllegalArgumentException("ground is not face-adjacent " + pos);
            BsiRecords.Block.of(pos.x(), pos.y(), pos.z(), 0, -1, 0);
            uniqueGround.add(pos);
        }
        ground = uniqueGround.stream().sorted(ORDER).toList();
        loads = List.copyOf(loads);
        var loaded = new HashSet<BlockKey>();
        for (var load : loads) {
            var pos = new BlockKey(load.x(), load.y(), load.z());
            if (!occupied.contains(pos) || !loaded.add(pos))
                throw new IllegalArgumentException("load needs one included structural cell " + pos);
            if (!Double.isFinite(load.fx()) || !Double.isFinite(load.fy()) || !Double.isFinite(load.fz()))
                throw new IllegalArgumentException("nonfinite force " + pos);
        }
    }

    /** Six candidate observations; the caller must read each actual contact face before including it. */
    public static List<BlockKey> neighbours(BlockKey p) {
        return List.of(new BlockKey(p.x()-1,p.y(),p.z()), new BlockKey(p.x()+1,p.y(),p.z()),
                new BlockKey(p.x(),p.y()-1,p.z()), new BlockKey(p.x(),p.y()+1,p.z()),
                new BlockKey(p.x(),p.y(),p.z()-1), new BlockKey(p.x(),p.y(),p.z()+1));
    }

    /** Wire input assembled only from this snapshot and the engine's returned identities. */
    public List<BsiRecords.Block> blocks(BsiVocabulary vocabulary) {
        var blocks = new ArrayList<BsiRecords.Block>(cells.size() + ground.size());
        for (var cell : cells) {
            var b = GameVocabulary.binding(cell.material(), cell.section());
            var p = cell.pos();
            blocks.add(new BsiRecords.Block(p.x(), p.y(), p.z(), b.materialId(vocabulary), b.sectionId(vocabulary),
                    cell.axis(), cell.joint(), cell.axisRot(), cell.fill(), cell.strength()));
        }
        if (!ground.isEmpty()) {
            int groundId = vocabulary.materialId("ground_rigid");
            for (var p : ground) blocks.add(BsiRecords.Block.of(p.x(), p.y(), p.z(), groundId, -1, 0));
        }
        return List.copyOf(BsiRecords.canonical(blocks));
    }

    /** Exact artifact sources from the same captured graph; adjacency is never re-inferred here. */
    public BsiFracture.World identified(UUID domain, ConstructionLedger.Graph graph, BsiVocabulary vocabulary) {
        if (!loads.isEmpty()) throw new IllegalArgumentException("fracture does not yet accept external loads");
        if (graph.owners().size() != cells.size()) throw new IllegalArgumentException("incomplete artifact graph");
        var owners = new ArrayList<BsiFracture.Owner>(cells.size());
        for (var cell : cells) {
            Long owner = graph.owners().get(cell.pos());
            if (owner == null) throw new IllegalArgumentException("missing artifact source " + cell.pos());
            var declaration = new ConstructionDeclaration(cell.material(),cell.section(),cell.axis()).groupDeclaration();
            if (!graph.records().get(owner).declaration().equals(declaration))
                throw new IllegalArgumentException("artifact declaration differs from source " + cell.pos());
            owners.add(new BsiFracture.Owner(cell.pos(),owner));
        }
        return new BsiFracture.World(new BsiFracture.Stamp(domain,revision.value()),graph.namespace(),blocks(vocabulary),owners);
    }
}
