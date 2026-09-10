package com.blockreality.core.transaction;

import com.blockreality.api.geom.BlockKey;
import java.io.IOException;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;
import static com.blockreality.core.transaction.MetadataCodec.*;
import static com.blockreality.core.transaction.ManufacturedPiece.Status.*;

/** Committed manufactured identity. The journal is its only durable authority; never observed grouping. */
public final class ManufacturedRegistry {
    public static final int MAX_LIFETIME_PIECES = 262144, MAX_OWNED_CELLS = 131072;
    private record Cell(String dimension, BlockKey position) { }
    private record Birth(String dimension, UUID actor, boolean creative, List<UUID> pieces) { }
    private record Update(ManufacturedPiece before, ManufacturedPiece after) { }
    private record Batch(Entry entry, Descriptor descriptor, long order, List<Update> updates) { }
    private final UUID domain;
    private final int pieceLimit, cellLimit;
    private final Map<UUID, ManufacturedPiece> pieces = new HashMap<>();
    private final Map<Cell, UUID> owners = new HashMap<>();
    private final Map<UUID, Birth> births = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private long order;
    private boolean failed;

    private ManufacturedRegistry(UUID domain, int pieceLimit, int cellLimit) {
        this.domain = Objects.requireNonNull(domain);
        if (pieceLimit < 1 || pieceLimit > MAX_LIFETIME_PIECES || cellLimit < 1 || cellLimit > MAX_OWNED_CELLS) throw invalid();
        this.pieceLimit = pieceLimit; this.cellLimit = cellLimit;
    }
    public static ManufacturedRegistry load(FileTransactionJournal journal) throws IOException {
        return load(journal,MAX_LIFETIME_PIECES,MAX_OWNED_CELLS);
    }
    // Tests may lower limits; no production caller can enlarge them.
    static ManufacturedRegistry load(FileTransactionJournal journal, int pieceLimit, int cellLimit) throws IOException {
        synchronized (journal) {
            var result = new ManufacturedRegistry(journal.domain(),pieceLimit,cellLimit);
            var sequence = new TreeMap<Long,UUID>();
            try {
                for (UUID id : journal.entryIds()) {
                    Entry entry = journal.read(id).orElseThrow(() -> new IOException("Metadata record disappeared"));
                    if (entry.phase() == Phase.REJECTED) continue;
                    Batch batch = parse(entry);
                    if (entry.phase() == Phase.COMMITTED && sequence.putIfAbsent(batch.order(),id) != null) throw invalid();
                }
                for (var step : sequence.entrySet()) {
                    Entry entry = journal.verify(step.getValue()).orElseThrow(() -> new IOException("Metadata record disappeared"));
                    Batch batch = parse(entry);
                    if (entry.phase() != Phase.COMMITTED || batch.order() != step.getKey()) throw invalid();
                    result.accept(batch);
                }
                return result;
            } catch (RuntimeException malformed) { throw new IOException("Manufactured history is inconsistent",malformed); }
        }
    }
    public UUID domain() { return domain; }
    public synchronized long order() { healthy(); return order; }
    public synchronized int lifetimePieces() { healthy(); return pieces.size(); }
    public synchronized int ownedCells() { healthy(); return owners.size(); }
    public synchronized long lastCommittedRevision(String dimension) { healthy(); dimension(dimension); return revisions.getOrDefault(dimension,0L); }
    public synchronized Optional<ManufacturedPiece> piece(UUID id) { healthy(); return Optional.ofNullable(pieces.get(Objects.requireNonNull(id))); }
    public synchronized Optional<UUID> owner(String dimension, BlockKey cell) {
        healthy(); dimension(dimension); ManufacturedPiece.position(cell); return Optional.ofNullable(owners.get(new Cell(dimension,cell)));
    }

    /** Private proposal until the coordinator commits its combined participant intent. */
    public record Prepared(Request request, List<Change> metadata, List<UUID> created, List<UUID> retired) {
        public Prepared { metadata = List.copyOf(metadata); created = List.copyOf(created); retired = List.copyOf(retired); }
        public Intent withParticipants(List<Change> participants) {
            var all = new ArrayList<>(metadata); all.addAll(participants);
            return new Intent(request,all,created,retired);
        }
    }
    public synchronized Prepared prepareBuild(Request request, String dimension, boolean creative, List<ManufacturedPiece.Plan> plans) {
        ready(request,dimension);
        if (plans.isEmpty() || plans.size() > MAX_PIECES || pieces.size() + plans.size() > pieceLimit) throw invalid();
        var cells = new HashSet<BlockKey>();
        for (var plan : plans) for (BlockKey cell : plan.cells()) {
            if (!cells.add(cell) || owners.containsKey(new Cell(dimension,cell))) throw invalid();
            if (cells.size() > MAX_CELLS || owners.size() + cells.size() > cellLimit) throw invalid();
        }
        var updates = new ArrayList<ManufacturedPiece>(); var ids = new HashSet<UUID>();
        for (var plan : plans) {
            UUID id = null;
            for (int attempt = 0; attempt < 16; attempt++) {
                UUID candidate = UUID.randomUUID();
                if (!pieces.containsKey(candidate) && ids.add(candidate)) { id = candidate; break; }
            }
            if (id == null) throw invalid();
            updates.add(new ManufacturedPiece(id,request.id(),request.actor(),dimension,plan.declaration(),order+1,
                    request.baseRevision()+1,INTACT,plan.cells()));
        }
        return prepare(request,new Descriptor(Operation.BUILD,dimension,creative,null),updates);
    }
    /** Release changed cells and/or mark dependencies. This does not perform the world edit. */
    public synchronized Prepared prepareEdit(Request request, String dimension, Set<BlockKey> released, Set<UUID> markIneligible) {
        ready(request,dimension);
        if (released.size() > MAX_CELLS || markIneligible.size() > MAX_PIECES) throw invalid();
        var affected = new HashSet<>(markIneligible);
        for (BlockKey cell : released) {
            ManufacturedPiece.position(cell); UUID owner = owners.get(new Cell(dimension,cell));
            if (owner != null) affected.add(owner);
        }
        if (affected.size() > MAX_PIECES) throw invalid();
        var updates = new ArrayList<ManufacturedPiece>();
        for (UUID id : affected) {
            ManufacturedPiece before = pieces.get(id);
            if (before == null || !before.dimension().equals(dimension) || before.status() == RETIRED) throw invalid();
            var after = before.edited(released,false); if (!before.equals(after)) updates.add(after);
        }
        return prepare(request,new Descriptor(Operation.EDIT,dimension,false,null),updates);
    }
    /** Metadata eligibility only. The service must separately validate original world/items and current permissions. */
    public synchronized Prepared prepareUndoMetadata(Request request, String dimension, UUID originalBuild) {
        ready(request,dimension); Birth birth = births.get(originalBuild);
        if (birth == null || !birth.dimension().equals(dimension) || !birth.actor().equals(request.actor())) throw invalid();
        var updates = new ArrayList<ManufacturedPiece>();
        for (UUID id : birth.pieces()) {
            ManufacturedPiece before = pieces.get(id);
            if (before.status() != INTACT) throw invalid();
            updates.add(before.edited(Set.of(),true));
        }
        return prepare(request,new Descriptor(Operation.UNDO,dimension,birth.creative(),originalBuild),updates);
    }
    private Prepared prepare(Request request, Descriptor descriptor, List<ManufacturedPiece> updates) {
        var changes = new ArrayList<Change>(); var created = new ArrayList<UUID>(); var retired = new ArrayList<UUID>();
        changes.add(new Change("meta/order",number(order),number(order+1)));
        changes.add(new Change(revisionKey(descriptor.dimension()),number(request.baseRevision()),number(request.baseRevision()+1)));
        changes.add(new Change("transaction/"+request.id(),Value.missing(),descriptor(descriptor)));
        for (ManufacturedPiece after : updates) {
            ManufacturedPiece before = pieces.get(after.id());
            changes.add(new Change("piece/"+after.id(),before == null ? Value.missing() : MetadataCodec.piece(before),MetadataCodec.piece(after)));
            if (before == null) created.add(after.id());
            if (after.status() == RETIRED) retired.add(after.id());
        }
        var result = new Prepared(request,changes,created,retired);
        validate(parse(Entry.prepared(result.withParticipants(List.of()))));
        return result;
    }
    /** Only a verified COMMITTED journal entry can publish new authoritative metadata. */
    public synchronized void applyCommitted(FileTransactionJournal journal, UUID id) throws IOException {
        healthy(); if (!domain.equals(journal.domain())) throw new IOException("Foreign construction journal");
        Optional<Entry> found;
        try { found = journal.verify(id); }
        catch (IOException failure) { failed = true; throw failure; }
        Entry entry = found.orElseThrow(() -> new IOException("Committed metadata missing"));
        if (entry.phase() != Phase.COMMITTED) throw new IOException("Metadata has no committed decision");
        try { accept(parse(entry)); }
        catch (RuntimeException malformed) {
            failed = true; throw new IOException("Committed metadata is inconsistent",malformed);
        }
    }
    private void ready(Request request, String dimension) {
        healthy(); dimension(dimension);
        if (!domain.equals(request.domain()) || order == Long.MAX_VALUE || request.baseRevision() < revisions.getOrDefault(dimension,0L)) throw invalid();
    }
    private void validate(Batch batch) {
        Request request = batch.entry().request(); Descriptor descriptor = batch.descriptor(); ready(request,descriptor.dimension());
        if (batch.order() != order+1 || batch.updates().size() > MAX_PIECES) throw invalid();
        var newIds = new TreeSet<UUID>(); var retired = new TreeSet<UUID>(); var touched = new HashSet<UUID>();
        var claimed = new HashSet<Cell>(); int cells = owners.size(), newCellCount = 0;
        for (Update update : batch.updates()) {
            ManufacturedPiece before = update.before(), after = update.after();
            if (!Objects.equals(pieces.get(after.id()),before) || !touched.add(after.id()) || !after.dimension().equals(descriptor.dimension())) throw invalid();
            if (before == null) {
                if (descriptor.operation() != Operation.BUILD || after.status() != INTACT
                        || !after.birthTransaction().equals(request.id()) || !after.actor().equals(request.actor())
                        || after.birthOrder() != batch.order() || after.birthRevision() != request.baseRevision()+1) throw invalid();
                newIds.add(after.id()); newCellCount += after.cells().size();
            } else {
                if (descriptor.operation() == Operation.BUILD || !before.sameBirth(after) || before.status() == RETIRED
                        || after.status() == INTACT || !new HashSet<>(before.cells()).containsAll(after.cells())) throw invalid();
                if (descriptor.operation() == Operation.UNDO && (before.status() != INTACT || after.status() != RETIRED
                        || !before.birthTransaction().equals(descriptor.original()))) throw invalid();
                cells -= before.cells().size();
            }
            if (after.status() == RETIRED) retired.add(after.id());
            cells += after.cells().size();
        }
        for (Update update : batch.updates()) for (BlockKey p : update.after().cells()) {
            Cell cell = new Cell(descriptor.dimension(),p); UUID owner = owners.get(cell);
            if (!claimed.add(cell) || owner != null && !owner.equals(update.after().id())) throw invalid();
        }
        if (pieces.size()+newIds.size() > pieceLimit || cells > cellLimit || newCellCount > MAX_CELLS
                || !List.copyOf(newIds).equals(batch.entry().intent().created())
                || !List.copyOf(retired).equals(batch.entry().intent().retired())) throw invalid();
        if (descriptor.operation() == Operation.BUILD && (newIds.isEmpty() || births.containsKey(request.id()))) throw invalid();
        if (descriptor.operation() == Operation.UNDO) {
            Birth birth = births.get(descriptor.original());
            if (birth == null || !birth.dimension().equals(descriptor.dimension()) || !birth.actor().equals(request.actor())
                    || birth.creative() != descriptor.creative() || !birth.pieces().equals(List.copyOf(retired))) throw invalid();
        }
    }
    private void accept(Batch batch) {
        validate(batch); failed = true; // A VM/allocation failure during publication leaves this instance unusable.
        for (Update update : batch.updates()) {
            if (update.before() != null) for (BlockKey cell : update.before().cells()) owners.remove(new Cell(update.before().dimension(),cell));
            ManufacturedPiece after = update.after(); pieces.put(after.id(),after);
            for (BlockKey cell : after.cells()) owners.put(new Cell(after.dimension(),cell),after.id());
        }
        Descriptor d = batch.descriptor(); Request r = batch.entry().request();
        if (d.operation() == Operation.BUILD) births.put(r.id(),new Birth(d.dimension(),r.actor(),d.creative(),batch.entry().intent().created()));
        order = batch.order(); revisions.put(d.dimension(),r.baseRevision()+1); failed = false;
    }
    private static Batch parse(Entry entry) {
        Intent intent = Objects.requireNonNull(entry.intent());
        var resources = new HashMap<String,Change>();
        for (Change change : intent.changes()) resources.put(change.resource(),change);
        Change descriptorChange = required(resources,"transaction/"+entry.request().id());
        if (descriptorChange.before().present()) throw invalid(); Descriptor descriptor = descriptor(descriptorChange.after());
        Change sequence = required(resources,"meta/order"), revision = required(resources,revisionKey(descriptor.dimension()));
        long before = number(sequence.before()), after = number(sequence.after());
        if (before == Long.MAX_VALUE || after != before+1 || number(revision.before()) != entry.request().baseRevision()
                || number(revision.after()) != intent.resultRevision()) throw invalid();
        var updates = new ArrayList<Update>();
        for (Change change : intent.changes()) {
            String key = change.resource();
            if (key.startsWith("piece/")) {
                UUID id = UUID.fromString(key.substring(6)); if (!key.equals("piece/"+id)) throw invalid();
                ManufacturedPiece prior = change.before().present() ? MetadataCodec.piece(change.before()) : null;
                ManufacturedPiece next = MetadataCodec.piece(change.after());
                if (!id.equals(next.id()) || prior != null && !id.equals(prior.id())) throw invalid();
                updates.add(new Update(prior,next));
            } else if (key.startsWith("meta/") && !key.equals("meta/order")
                    || key.startsWith("revision/") && !key.equals(revision.resource())
                    || key.startsWith("transaction/") && !key.equals(descriptorChange.resource())) throw invalid();
        }
        if (updates.size() > MAX_PIECES) throw invalid();
        return new Batch(entry,descriptor,after,List.copyOf(updates));
    }
    private static Change required(Map<String,Change> changes, String key) {
        Change change = changes.get(key); if (change == null) throw invalid(); return change;
    }
    private void healthy() { if (failed) throw new IllegalStateException("Manufactured registry requires reconstruction"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid manufactured history or proposal"); }
}
