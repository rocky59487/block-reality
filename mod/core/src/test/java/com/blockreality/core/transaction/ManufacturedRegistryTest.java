package com.blockreality.core.transaction;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.world.ConstructionDeclaration;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;
import static com.blockreality.core.transaction.TransactionFixtures.*;

class ManufacturedRegistryTest {
    @TempDir Path root;
    static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
    static final ConstructionDeclaration STEEL = new ConstructionDeclaration("steel","steel_rect_200x400",0);

    @Test void validatedPendingContextCannotExposeForeignMetadataAsRecoveryAuthority() throws Exception {
        try (var journal = new FileTransactionJournal(root,DOMAIN)) {
            var registry = ManufacturedRegistry.load(journal);
            commit(journal,registry,registry.prepareBuild(request(1,7),OVERWORLD,false,List.of(run(0,2))));
            var proposal = registry.prepareEdit(request(2,9),OVERWORLD,Set.of(new BlockKey(0,80,0)),Set.of());
            var opaque = new Change("cell/0/80/0",value("before"),value("after"));
            Intent intent = proposal.withParticipants(List.of(opaque));
            var context = registry.validatePrepared(Entry.prepared(intent));
            assertEquals(OVERWORLD,context.dimension()); assertEquals("EDIT",context.operation());
            assertTrue(context.revisionResource().startsWith("revision/"));
            assertEquals(proposal.metadata().stream().sorted(Comparator.comparing(Change::resource)).toList(),context.changes());
            assertThrows(UnsupportedOperationException.class,()->context.changes().clear());
            Change piece = proposal.metadata().stream().filter(c -> c.resource().startsWith("piece/")).findFirst().orElseThrow();
            Intent foreignBefore = replace(intent,new Change(piece.resource(),Value.missing(),piece.after()));
            assertThrows(IOException.class,()->registry.validatePrepared(Entry.prepared(foreignBefore)));
            Request original = intent.request();
            Request foreign = new Request(original.id(),original.actor(),original.session(),UUID.randomUUID(),original.baseRevision(),original.planHash());
            assertThrows(IOException.class,()->registry.validatePrepared(Entry.prepared(new Intent(foreign,intent.changes(),intent.created(),intent.retired()))));
            assertThrows(IOException.class,()->registry.validatePrepared(Entry.prepared(intent).finish(Phase.COMMITTED,Reason.NONE)));
            assertEquals(1,registry.order()); assertEquals(2,registry.ownedCells());
            assertEquals(context,registry.validatePrepared(Entry.prepared(intent)));
        }
    }
    @Test void committedCoverageIsImmutableDimensionScopedAndTracksReleasedOwnership() throws Exception {
        try (var journal = new FileTransactionJournal(root,DOMAIN)) {
            var registry = ManufacturedRegistry.load(journal);
            commit(journal,registry,registry.prepareBuild(request(1,0),OVERWORLD,false,List.of(run(3,2),run(0,2))));
            commit(journal,registry,registry.prepareBuild(request(2,0),NETHER,false,List.of(run(-2,1))));
            var coverage = registry.ownedCells(OVERWORLD);
            assertEquals(List.of(new BlockKey(0,80,0),new BlockKey(1,80,0),new BlockKey(3,80,0),new BlockKey(4,80,0)),coverage);
            assertThrows(UnsupportedOperationException.class,coverage::clear);
            commit(journal,registry,registry.prepareEdit(request(3,1),OVERWORLD,Set.of(new BlockKey(0,80,0)),Set.of()));
            assertEquals(4,coverage.size()); assertEquals(3,registry.ownedCells(OVERWORLD).size());
            assertEquals(List.of(new BlockKey(-2,80,0)),registry.ownedCells(NETHER));
            assertEquals(registry.ownedCells(OVERWORLD),ManufacturedRegistry.load(journal).ownedCells(OVERWORLD));
        }
    }
    @Test void abortedBeforeRevisionSurvivesRepeatedReopenWithoutPromotingRejectedRequests() throws Exception {
        try (var journal = new FileTransactionJournal(root,DOMAIN)) {
            var registry = ManufacturedRegistry.load(journal);
            store(journal,registry.prepareEdit(request(1,17),OVERWORLD,Set.of(),Set.of()),Phase.ABORTED);
            journal.create(Entry.rejected(request(2,999),Reason.STALE_REVISION));
            commit(journal,registry,registry.prepareEdit(request(3,2),NETHER,Set.of(),Set.of()));
            for (int i=0;i<2;i++) {
                registry = ManufacturedRegistry.load(journal);
                assertEquals(17,registry.worldRevisionFloor(OVERWORLD)); assertEquals(0,registry.lastCommittedRevision(OVERWORLD));
                assertEquals(3,registry.worldRevisionFloor(NETHER)); assertEquals(3,registry.lastCommittedRevision(NETHER));
                assertEquals(0,registry.ownedCells()); assertEquals(1,registry.order());
            }
        }
        try (var journal = FileTransactionJournal.open(root)) {
            assertEquals(17,ManufacturedRegistry.load(journal).worldRevisionFloor(OVERWORLD));
        }
    }
    static ManufacturedPiece.Plan run(int start, int count) {
        var cells = new ArrayList<BlockKey>(); for (int x = start; x < start+count; x++) cells.add(new BlockKey(x,80,0));
        return new ManufacturedPiece.Plan(STEEL,cells);
    }
    static Entry store(FileTransactionJournal journal, ManufacturedRegistry.Prepared prepared, Phase phase) throws Exception {
        journal.create(Entry.prepared(prepared.withParticipants(List.of())));
        return phase == Phase.PREPARED ? journal.read(prepared.request().id()).orElseThrow()
                : journal.decide(prepared.request().id(),phase,phase == Phase.COMMITTED ? Reason.NONE : Reason.APPLY_FAILED);
    }
    static void commit(FileTransactionJournal journal, ManufacturedRegistry registry, ManufacturedRegistry.Prepared prepared) throws Exception {
        store(journal,prepared,Phase.COMMITTED); registry.applyCommitted(journal,prepared.request().id());
    }
    static Intent replace(Intent intent, Change replacement) {
        var changes = new ArrayList<>(intent.changes()); changes.removeIf(c -> c.resource().equals(replacement.resource())); changes.add(replacement);
        return new Intent(intent.request(),changes,intent.created(),intent.retired());
    }
    static void forgedCommit(FileTransactionJournal journal, Intent intent) throws Exception {
        journal.create(Entry.prepared(intent)); journal.decide(intent.request().id(),Phase.COMMITTED,Reason.NONE);
    }

    @Test void declaredBoundariesValidateRunsConnectedCellsAxesAndWorldLimits() {
        assertEquals(3,run(1,3).cells().size());
        assertThrows(IllegalArgumentException.class,()->new ManufacturedPiece.Plan(STEEL,List.of(new BlockKey(1,80,0),new BlockKey(3,80,0))));
        assertThrows(IllegalArgumentException.class,()->new ManufacturedPiece.Plan(STEEL,List.of(new BlockKey(1,80,0),new BlockKey(1,81,0))));
        assertThrows(IllegalArgumentException.class,()->new ManufacturedPiece.Plan(STEEL,List.of(new BlockKey(1,80,0),new BlockKey(1,80,0))));
        assertThrows(IllegalArgumentException.class,()->new ManufacturedPiece.Plan(new ConstructionDeclaration("steel","steel_rect_200x400",-1),run(1,1).cells()));
        for (BlockKey cell : List.of(new BlockKey(30000000,0,0),new BlockKey(0,2048,0),new BlockKey(0,0,-30000001)))
            assertThrows(IllegalArgumentException.class,()->new ManufacturedPiece.Plan(STEEL,List.of(cell)));
        var panel = new ConstructionDeclaration("concrete","concrete_slab_200",1);
        var elbow = List.of(new BlockKey(0,80,0),new BlockKey(1,80,0),new BlockKey(1,80,1));
        assertEquals(3,new ManufacturedPiece.Plan(panel,elbow).cells().size());
        assertThrows(IllegalArgumentException.class,()->new ManufacturedPiece.Plan(panel,List.of(new BlockKey(0,80,0),new BlockKey(1,80,1))));
        for (int axis : List.of(1,2)) {
            var declaration = new ConstructionDeclaration("steel","steel_rect_200x400",axis);
            var cells = axis == 1 ? List.of(new BlockKey(0,81,0),new BlockKey(0,80,0)) : List.of(new BlockKey(0,80,1),new BlockKey(0,80,0));
            assertEquals(new BlockKey(0,80,0),new ManufacturedPiece.Plan(declaration,cells).cells().get(0));
        }
    }

    @Test void previewDoesNotPublishAndAdjacentEqualProductsRemainSeparateAcrossReopen() throws Exception {
        UUID left,right;
        try (var journal = new FileTransactionJournal(root,DOMAIN)) {
            var registry = ManufacturedRegistry.load(journal);
            var plan = registry.prepareBuild(request(1,7),OVERWORLD,false,List.of(run(0,2),run(2,2)));
            assertEquals(0,registry.lifetimePieces()); assertEquals(0,registry.order()); assertEquals(0,journal.keyCount());
            store(journal,plan,Phase.PREPARED);
            assertThrows(IOException.class,()->registry.applyCommitted(journal,plan.request().id()));
            assertTrue(registry.owner(OVERWORLD,new BlockKey(0,80,0)).isEmpty());
            journal.decide(plan.request().id(),Phase.COMMITTED,Reason.NONE); registry.applyCommitted(journal,plan.request().id());
            left=registry.owner(OVERWORLD,new BlockKey(0,80,0)).orElseThrow();right=registry.owner(OVERWORLD,new BlockKey(2,80,0)).orElseThrow();
            assertNotEquals(left,right); assertEquals(2,registry.lifetimePieces()); assertEquals(4,registry.ownedCells());
            assertEquals(1,registry.order()); assertEquals(8,registry.lastCommittedRevision(OVERWORLD));
            assertThrows(UnsupportedOperationException.class,()->registry.piece(left).orElseThrow().cells().clear());
            assertThrows(UnsupportedOperationException.class,()->plan.metadata().clear());
        }
        try (var journal = FileTransactionJournal.open(root)) {
            var registry = ManufacturedRegistry.load(journal);
            assertEquals(left,registry.owner(OVERWORLD,new BlockKey(1,80,0)).orElseThrow());
            assertEquals(right,registry.owner(OVERWORLD,new BlockKey(3,80,0)).orElseThrow());
        }
    }

    @Test void dimensionsOwnSeparateCellsAndPermitOnlyMonotonicCommittedRevisionBindings() throws Exception {
        try (var journal = new FileTransactionJournal(root,DOMAIN)) {
            var registry=ManufacturedRegistry.load(journal);
            commit(journal,registry,registry.prepareBuild(request(1,20),OVERWORLD,false,List.of(run(0,1))));
            commit(journal,registry,registry.prepareBuild(request(2,0),NETHER,false,List.of(run(0,1))));
            commit(journal,registry,registry.prepareBuild(request(3,25),OVERWORLD,false,List.of(run(3,1))));
            assertEquals(3,registry.order()); assertEquals(26,registry.lastCommittedRevision(OVERWORLD)); assertEquals(1,registry.lastCommittedRevision(NETHER));
            assertNotEquals(registry.owner(OVERWORLD,new BlockKey(0,80,0)),registry.owner(NETHER,new BlockKey(0,80,0)));
            assertThrows(IllegalArgumentException.class,()->registry.prepareBuild(request(4,24),OVERWORLD,false,List.of(run(4,1))));
            assertThrows(IllegalArgumentException.class,()->registry.prepareBuild(request(4,26),"bad dimension",false,List.of(run(4,1))));
            var restored=ManufacturedRegistry.load(journal); assertEquals(3,restored.order()); assertEquals(26,restored.lastCommittedRevision(OVERWORLD));
        }
    }

    @Test void planningConflictsAndLifetimeOrCellCapacityRefuseWithoutPartialAdmission() throws Exception {
        try (var journal = new FileTransactionJournal(root,DOMAIN)) {
            var registry=ManufacturedRegistry.load(journal,2,3);
            commit(journal,registry,registry.prepareBuild(request(1,0),OVERWORLD,false,List.of(run(0,2))));
            assertThrows(IllegalArgumentException.class,()->registry.prepareBuild(request(2,1),OVERWORLD,false,List.of(run(1,1))));
            assertThrows(IllegalArgumentException.class,()->registry.prepareBuild(request(2,1),OVERWORLD,false,List.of(run(3,2))));
            assertThrows(IllegalArgumentException.class,()->registry.prepareBuild(request(2,1),OVERWORLD,false,List.of(run(3,1),run(3,1))));
            assertEquals(1,registry.lifetimePieces()); assertEquals(2,registry.ownedCells()); assertEquals(1,journal.keyCount());
            commit(journal,registry,registry.prepareUndoMetadata(request(2,1),OVERWORLD,request(1,0).id()));
            commit(journal,registry,registry.prepareBuild(request(3,2),OVERWORLD,false,List.of(run(0,1))));
            assertThrows(IllegalArgumentException.class,()->registry.prepareBuild(request(4,3),OVERWORLD,false,List.of(run(5,1))));
            assertEquals(2,registry.lifetimePieces()); assertEquals(1,registry.ownedCells());
        }
    }

    @Test void cutPreservesOriginalIdentityAndNeverRestoresUndoEligibility() throws Exception {
        try (var journal = new FileTransactionJournal(root,DOMAIN)) {
            var registry=ManufacturedRegistry.load(journal);
            commit(journal,registry,registry.prepareBuild(request(1,0),OVERWORLD,false,List.of(run(0,3))));
            UUID id=registry.owner(OVERWORLD,new BlockKey(0,80,0)).orElseThrow();
            commit(journal,registry,registry.prepareEdit(request(2,1),OVERWORLD,Set.of(new BlockKey(1,80,0)),Set.of()));
            var edited=registry.piece(id).orElseThrow(); assertEquals(ManufacturedPiece.Status.EDITED,edited.status());
            assertEquals(List.of(new BlockKey(0,80,0),new BlockKey(2,80,0)),edited.cells());
            assertTrue(registry.owner(OVERWORLD,new BlockKey(1,80,0)).isEmpty());
            assertThrows(IllegalArgumentException.class,()->registry.prepareUndoMetadata(request(3,2),OVERWORLD,request(1,0).id()));
            commit(journal,registry,registry.prepareBuild(request(3,2),OVERWORLD,false,List.of(run(1,1))));
            assertNotEquals(id,registry.owner(OVERWORLD,new BlockKey(1,80,0)).orElseThrow());
            assertThrows(IllegalArgumentException.class,()->registry.prepareUndoMetadata(request(4,3),OVERWORLD,request(1,0).id()));
            assertEquals(ManufacturedPiece.Status.EDITED,ManufacturedRegistry.load(journal).piece(id).orElseThrow().status());
        }
    }

    @Test void dependencyMarkAndEmptyEditsRetainPermanentTombstones() throws Exception {
        try (var journal = new FileTransactionJournal(root,DOMAIN)) {
            var registry=ManufacturedRegistry.load(journal);
            commit(journal,registry,registry.prepareBuild(request(1,0),OVERWORLD,false,List.of(run(0,1))));
            UUID id=registry.owner(OVERWORLD,new BlockKey(0,80,0)).orElseThrow();
            commit(journal,registry,registry.prepareEdit(request(2,1),OVERWORLD,Set.of(),Set.of(id)));
            assertEquals(1,registry.ownedCells()); assertEquals(ManufacturedPiece.Status.EDITED,registry.piece(id).orElseThrow().status());
            assertThrows(IllegalArgumentException.class,()->registry.prepareUndoMetadata(request(3,2),OVERWORLD,request(1,0).id()));
            commit(journal,registry,registry.prepareEdit(request(3,2),OVERWORLD,Set.of(new BlockKey(0,80,0)),Set.of()));
            var retired=registry.piece(id).orElseThrow(); assertEquals(ManufacturedPiece.Status.RETIRED,retired.status());
            assertTrue(retired.cells().isEmpty()); assertEquals(request(1,0).id(),retired.birthTransaction());
            assertEquals(0,registry.ownedCells()); assertEquals(1,ManufacturedRegistry.load(journal).lifetimePieces());
        }
    }

    @Test void wholeBuildUndoRetiresAllIdsAndPreservesCreativeAndActorBinding() throws Exception {
        for (boolean creative : List.of(false,true)) try (var journal = new FileTransactionJournal(root.resolve("mode-"+creative),DOMAIN)) {
            var registry=ManufacturedRegistry.load(journal);
            var build=registry.prepareBuild(request(1,0),OVERWORLD,creative,List.of(run(0,1),run(2,1)));
            commit(journal,registry,build);
            var foreign=new Request(new UUID(10,2),new UUID(99,99),SESSION,DOMAIN,1,"ab".repeat(32));
            assertThrows(IllegalArgumentException.class,()->registry.prepareUndoMetadata(foreign,OVERWORLD,build.request().id()));
            assertThrows(IllegalArgumentException.class,()->registry.prepareUndoMetadata(request(2,1),NETHER,build.request().id()));
            var undo=registry.prepareUndoMetadata(request(2,1),OVERWORLD,build.request().id());
            var descriptor=undo.metadata().stream().filter(c->c.resource().startsWith("transaction/")).findFirst().orElseThrow();
            assertEquals(creative,MetadataCodec.descriptor(descriptor.after()).creative());
            assertEquals(new TreeSet<>(build.created()),new TreeSet<>(undo.retired())); commit(journal,registry,undo);
            assertEquals(0,registry.ownedCells()); assertEquals(2,registry.lifetimePieces());
            assertThrows(IllegalArgumentException.class,()->registry.prepareUndoMetadata(request(3,2),OVERWORLD,build.request().id()));
            for (UUID id : build.created()) assertEquals(ManufacturedPiece.Status.RETIRED,ManufacturedRegistry.load(journal).piece(id).orElseThrow().status());
        }
    }

    @Test void mixedPhasesNeverReplayOldWorldOrPlayerImages() throws Exception {
        Path world=Files.write(root.resolve("world.current"),new byte[]{9,8,7});
        try (var journal = new FileTransactionJournal(root.resolve("journal"),DOMAIN)) {
            var registry=ManufacturedRegistry.load(journal);
            var build=registry.prepareBuild(request(1,0),OVERWORLD,false,List.of(run(0,1)));
            var intent=build.withParticipants(List.of(new Change("world/opaque",Value.of(new byte[]{1}),Value.of(new byte[]{2})),
                    new Change("inventory/opaque",Value.of(new byte[]{3}),Value.of(new byte[]{4}))));
            forgedCommit(journal,intent);registry.applyCommitted(journal,build.request().id());
            store(journal,registry.prepareBuild(request(2,1),OVERWORLD,false,List.of(run(2,1))),Phase.ABORTED);
            journal.create(Entry.rejected(request(3,1),Reason.VALIDATION_REFUSED));
            store(journal,registry.prepareBuild(request(4,1),OVERWORLD,false,List.of(run(4,1))),Phase.PREPARED);
            var loaded=ManufacturedRegistry.load(journal);
            assertEquals(1,loaded.order());assertEquals(1,loaded.lifetimePieces());
            assertTrue(loaded.owner(OVERWORLD,new BlockKey(2,80,0)).isEmpty());assertTrue(loaded.owner(OVERWORLD,new BlockKey(4,80,0)).isEmpty());
            assertEquals(Optional.of(request(4,1).id()),journal.pending());assertArrayEquals(new byte[]{9,8,7},Files.readAllBytes(world));
        }
    }

    @Test void forgedOwnershipCannotPublishOrReconstructEvenWithValidJournalChecksums() throws Exception {
        try (var journal = new FileTransactionJournal(root,DOMAIN)) {
            var registry=ManufacturedRegistry.load(journal);
            commit(journal,registry,registry.prepareBuild(request(1,0),OVERWORLD,false,List.of(run(0,1))));
            UUID owner=registry.owner(OVERWORLD,new BlockKey(0,80,0)).orElseThrow();
            var planned=registry.prepareBuild(request(2,1),OVERWORLD,false,List.of(run(2,1))).withParticipants(List.of());
            var change=planned.changes().stream().filter(c->c.resource().startsWith("piece/")).findFirst().orElseThrow();
            var p=MetadataCodec.piece(change.after());
            var stolen=new ManufacturedPiece(p.id(),p.birthTransaction(),p.actor(),p.dimension(),p.declaration(),p.birthOrder(),p.birthRevision(),p.status(),run(0,1).cells());
            forgedCommit(journal,replace(planned,new Change(change.resource(),change.before(),MetadataCodec.piece(stolen))));
            assertThrows(IOException.class,()->registry.applyCommitted(journal,planned.request().id()));
            assertThrows(IllegalStateException.class,registry::order);
            assertThrows(IllegalStateException.class,()->registry.owner(OVERWORLD,new BlockKey(0,80,0)));
            assertThrows(IllegalStateException.class,()->registry.prepareBuild(request(3,2),OVERWORLD,false,List.of(run(4,1))));
            assertThrows(IOException.class,()->ManufacturedRegistry.load(journal));
        }
    }

    @Test void wrongBeforeImagesBirthReceiptsRevisionAndOrderRefuseWithoutMutation() throws Exception {
        for (String mode : List.of("order","revision","birth","created","unknown-key","before")) {
            try (var journal = new FileTransactionJournal(root.resolve(mode),DOMAIN)) {
                var registry=ManufacturedRegistry.load(journal);
                var planned=registry.prepareBuild(request(1,0),OVERWORLD,false,List.of(run(0,1))).withParticipants(List.of());
                switch (mode) {
                    case "order" -> planned=replace(planned,new Change("meta/order",MetadataCodec.number(1),MetadataCodec.number(2)));
                    case "revision" -> planned=replace(planned,new Change(MetadataCodec.revisionKey(OVERWORLD),MetadataCodec.number(2),MetadataCodec.number(3)));
                    case "created" -> planned=new Intent(planned.request(),planned.changes(),List.of(),List.of());
                    case "unknown-key" -> {
                        var changes=new ArrayList<>(planned.changes());changes.add(new Change("meta/unknown",Value.missing(),Value.of(new byte[]{1})));
                        planned=new Intent(planned.request(),changes,planned.created(),planned.retired());
                    }
                    case "birth","before" -> {
                        var c=planned.changes().stream().filter(x->x.resource().startsWith("piece/")).findFirst().orElseThrow();
                        var p=MetadataCodec.piece(c.after());
                        if (mode.equals("birth")) p=new ManufacturedPiece(p.id(),new UUID(99,99),p.actor(),p.dimension(),p.declaration(),p.birthOrder(),p.birthRevision(),p.status(),p.cells());
                        planned=replace(planned,new Change(c.resource(),mode.equals("before") ? MetadataCodec.piece(p.edited(Set.of(),false)) : c.before(),MetadataCodec.piece(p)));
                    }
                }
                forgedCommit(journal,planned);UUID id=planned.request().id();
                assertThrows(IOException.class,()->registry.applyCommitted(journal,id),mode);
                assertThrows(IllegalStateException.class,registry::order);assertThrows(IllegalStateException.class,registry::lifetimePieces);
                assertThrows(IOException.class,()->ManufacturedRegistry.load(journal),mode);
            }
        }
    }

    @Test void forgedPartialUndoCreativeModeAndActorCannotAuthorizeAnInverse() throws Exception {
        for (String mode : List.of("subset","creative","actor")) try (var journal=new FileTransactionJournal(root.resolve(mode),DOMAIN)) {
            var registry=ManufacturedRegistry.load(journal);
            commit(journal,registry,registry.prepareBuild(request(1,0),OVERWORLD,true,List.of(run(0,1),run(2,1))));
            Intent undo=registry.prepareUndoMetadata(request(2,1),OVERWORLD,request(1,0).id()).withParticipants(List.of());
            if (mode.equals("subset")) {
                UUID only=undo.retired().get(0);
                var changes=undo.changes().stream().filter(c->!c.resource().startsWith("piece/") || c.resource().equals("piece/"+only)).toList();
                undo=new Intent(undo.request(),changes,List.of(),List.of(only));
            } else if (mode.equals("creative")) {
                undo=replace(undo,new Change("transaction/"+undo.request().id(),Value.missing(),MetadataCodec.descriptor(
                        new MetadataCodec.Descriptor(MetadataCodec.Operation.UNDO,OVERWORLD,false,request(1,0).id()))));
            } else {
                var before=undo.request();var foreign=new Request(before.id(),new UUID(90,90),before.session(),DOMAIN,before.baseRevision(),before.planHash());
                undo=new Intent(foreign,undo.changes(),undo.created(),undo.retired());
            }
            forgedCommit(journal,undo);UUID id=undo.request().id();
            assertThrows(IOException.class,()->registry.applyCommitted(journal,id));
            assertThrows(IllegalStateException.class,registry::ownedCells);
            assertThrows(IOException.class,()->ManufacturedRegistry.load(journal));
        }
    }

    @Test void retiredIdCannotBeRebornEvenWithNewBirthFieldsAndAValidEnvelope() throws Exception {
        try (var journal=new FileTransactionJournal(root,DOMAIN)) {
            var registry=ManufacturedRegistry.load(journal);
            var build=registry.prepareBuild(request(1,0),OVERWORLD,false,List.of(run(0,1)));commit(journal,registry,build);
            UUID retired=build.created().get(0);commit(journal,registry,registry.prepareUndoMetadata(request(2,1),OVERWORLD,build.request().id()));
            var next=registry.prepareBuild(request(3,2),OVERWORLD,false,List.of(run(4,1))).withParticipants(List.of());
            var change=next.changes().stream().filter(c->c.resource().startsWith("piece/")).findFirst().orElseThrow();
            var p=MetadataCodec.piece(change.after());
            var reborn=new ManufacturedPiece(retired,p.birthTransaction(),p.actor(),p.dimension(),p.declaration(),p.birthOrder(),p.birthRevision(),p.status(),p.cells());
            var changes=new ArrayList<>(next.changes());changes.remove(change);
            changes.add(new Change("piece/"+retired,Value.missing(),MetadataCodec.piece(reborn)));
            forgedCommit(journal,new Intent(next.request(),changes,List.of(retired),List.of()));
            assertThrows(IOException.class,()->registry.applyCommitted(journal,next.request().id()));
            assertThrows(IOException.class,()->ManufacturedRegistry.load(journal));
        }
    }

    @Test void journalKeyOrderIsNotCommitOrderAndDuplicateSequenceIsRefused() throws Exception {
        try (var journal=new FileTransactionJournal(root,DOMAIN)) {
            var registry=ManufacturedRegistry.load(journal);
            commit(journal,registry,registry.prepareBuild(request(9,0),OVERWORLD,false,List.of(run(0,1))));
            commit(journal,registry,registry.prepareBuild(request(1,1),OVERWORLD,false,List.of(run(2,1))));
            commit(journal,registry,registry.prepareBuild(request(5,2),OVERWORLD,false,List.of(run(4,1))));
            assertEquals(List.of(request(1,0).id(),request(5,0).id(),request(9,0).id()),journal.entryIds());
            assertEquals(3,ManufacturedRegistry.load(journal).order());
            Intent duplicate=registry.prepareBuild(request(3,3),OVERWORLD,false,List.of(run(6,1))).withParticipants(List.of());
            duplicate=replace(duplicate,new Change("meta/order",MetadataCodec.number(0),MetadataCodec.number(1)));
            var change=duplicate.changes().stream().filter(c->c.resource().startsWith("piece/")).findFirst().orElseThrow();
            var p=MetadataCodec.piece(change.after());
            var rewound=new ManufacturedPiece(p.id(),p.birthTransaction(),p.actor(),p.dimension(),p.declaration(),1,p.birthRevision(),p.status(),p.cells());
            duplicate=replace(duplicate,new Change(change.resource(),change.before(),MetadataCodec.piece(rewound)));
            forgedCommit(journal,duplicate);
            assertThrows(IOException.class,()->ManufacturedRegistry.load(journal));
        }
    }

    @Test void PrematureMissingAndForeignPublicationRefuseButVerificationFailureLatches() throws Exception {
        try (var journal=new FileTransactionJournal(root.resolve("journal"),DOMAIN);
             var foreign=new FileTransactionJournal(root.resolve("foreign"),new UUID(80,80))) {
            var registry=ManufacturedRegistry.load(journal);
            assertThrows(IOException.class,()->registry.applyCommitted(journal,request(1,0).id()));
            assertThrows(IOException.class,()->registry.applyCommitted(foreign,request(1,0).id()));
            assertEquals(0,registry.order());
            var prepared=registry.prepareBuild(request(1,0),OVERWORLD,false,List.of(run(0,1)));
            store(journal,prepared,Phase.PREPARED);
            assertThrows(IOException.class,()->registry.applyCommitted(journal,prepared.request().id()));assertEquals(0,registry.order());
            journal.decide(prepared.request().id(),Phase.COMMITTED,Reason.NONE);
            Path record=root.resolve("journal/txn-"+prepared.request().id()+".brtx");
            byte[] bytes=Files.readAllBytes(record);bytes[bytes.length-1]^=1;Files.write(record,bytes);
            assertThrows(IOException.class,()->registry.applyCommitted(journal,prepared.request().id()));
            assertThrows(IllegalStateException.class,()->registry.prepareEdit(request(2,1),OVERWORLD,Set.of(),Set.of()));
            assertThrows(IllegalStateException.class,registry::order);
        }
    }
}
