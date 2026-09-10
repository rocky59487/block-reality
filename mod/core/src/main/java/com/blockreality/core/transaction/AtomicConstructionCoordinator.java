package com.blockreality.core.transaction;

import java.io.IOException;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

/**
 * Serial module transaction coordinator. The Forge adapter owns validation, canonical images,
 * participant durability, server-thread exclusion and published visibility. No engine work occurs
 * here. A new coordinator starts closed to writes until recovery and baseline publication succeed.
 */
public final class AtomicConstructionCoordinator {
    /**
     * The host must exclude game/player/worker access to provisional participants. All images are
     * server-authored, with the persistent revision among them. write/flush must not publish block
     * updates, inventory synchronization, artifact visibility or native analysis. flush must wait
     * for every affected world/player/module participant, including restores, to reach storage.
     */
    public interface Host {
        void checkAccess();
        long revision();
        Value read(String resource) throws Exception;
        void write(String resource, Value value) throws Exception;
        void flush(List<String> resources) throws Exception;
        void publish(Receipt receipt) throws Exception;
        /** Run during startup/recovery before gameplay and analysis can resume. */
        void publishRecoveredState() throws Exception;
    }
    @FunctionalInterface public interface Preparer {
        /** Pure validation/capture: no permanent IDs, items, world edits, or external publication. */
        Intent prepare(Request request) throws Exception;
    }
    public static final class ValidationRefused extends Exception {
        private static final long serialVersionUID = 1L;
        public ValidationRefused() { super("Construction validation refused"); }
    }
    public static final class ReplayConflict extends IOException {
        private static final long serialVersionUID = 1L;
        ReplayConflict() { super("Transaction key is bound to another request"); }
    }
    public static final class RecoveryRequired extends IOException {
        private static final long serialVersionUID = 1L;
        RecoveryRequired(Throwable cause) { super("Construction recovery required", cause); }
    }

    private final TransactionJournal journal;
    private boolean ready, busy;
    public AtomicConstructionCoordinator(TransactionJournal journal) { this.journal = Objects.requireNonNull(journal); }
    public synchronized boolean ready() { return ready; }

    public synchronized Receipt execute(Request request, Preparer preparer, Host host) throws IOException {
        enter(host);
        try {
            if (!journal.domain().equals(request.domain())) throw new ReplayConflict();
            Optional<Entry> previous;
            try { previous = journal.read(request.id()); }
            catch (IOException failure) { throw recovery(failure); }
            if (previous.isPresent() && !previous.get().request().equals(request)) throw new ReplayConflict();
            if (!ready) throw new RecoveryRequired(null);
            try { if (journal.pending().isPresent()) throw recovery(null); }
            catch (IOException failure) { throw recovery(failure); }
            if (previous.isPresent()) {
                if (previous.get().phase() == Phase.PREPARED) throw recovery(null);
                return previous.get().receipt();
            }
            if (host.revision() != request.baseRevision()) return reject(request, Reason.STALE_REVISION);
            Intent intent;
            try { intent = Objects.requireNonNull(preparer.prepare(request)); }
            catch (ValidationRefused refused) { return reject(request, Reason.VALIDATION_REFUSED); }
            catch (Exception failure) { throw new IOException("Construction preparation failed without a decision", failure); }
            if (!intent.request().equals(request)) throw new IllegalArgumentException("Preparer changed request binding");
            try {
                if (host.revision() != request.baseRevision() || !matches(intent, host, false))
                    return reject(request, Reason.PARTICIPANT_CONFLICT);
            } catch (IOException failure) { throw failure; }
            catch (Exception failure) { throw new IOException("Construction capture verification failed", failure); }

            Entry decision;
            boolean preparedAcknowledged = false;
            ready = false; // An Error/process interruption leaves the instance closed, even if finally runs.
            try {
                journal.create(Entry.prepared(intent));
                preparedAcknowledged = true;
                for (Change change : intent.changes()) host.write(change.resource(), change.after());
                requireState(intent, host, true);
                host.flush(resources(intent));
                requireState(intent, host, true);
                decision = journal.decide(request.id(), Phase.COMMITTED, Reason.NONE);
            } catch (Exception failure) {
                Optional<Entry> actual = resolve(request.id(), failure);
                if (actual.isEmpty()) {
                    if (preparedAcknowledged) throw recovery(failure);
                    // PREPARED never existed, so no host write was reached.
                    ready = true; throw new IOException("Construction intent was not stored", failure);
                }
                Entry found = actual.get();
                if (!found.request().equals(request) || !Objects.equals(found.intent(), intent)) throw recovery(failure);
                if (found.phase() == Phase.COMMITTED) decision = found; // Lost decision ACK cannot undo history.
                else if (found.phase() == Phase.PREPARED) {
                    try { decision = rollback(found, host, Reason.APPLY_FAILED); }
                    catch (RecoveryRequired blocked) { blocked.addSuppressed(failure); throw blocked; }
                    ready = true; return decision.receipt();
                } else throw recovery(failure);
            }
            // Deliberately outside the rollback catch. Publication failure cannot reverse COMMITTED.
            try { host.publish(decision.receipt()); }
            catch (Exception failure) { throw recovery(failure); }
            ready = true; return decision.receipt();
        } finally { busy = false; }
    }

    /** PREPARED rolls back; terminal decisions never replay old images over later legitimate edits. */
    public synchronized void recover(Host host) throws IOException {
        enter(host); ready = false;
        try {
            Optional<UUID> pending = journal.pending();
            if (pending.isPresent()) {
                Entry entry = journal.verify(pending.get()).orElseThrow(() -> new IOException("Prepared intent disappeared"));
                if (entry.phase() == Phase.PREPARED) rollback(entry, host, Reason.RECOVERED);
            }
            host.publishRecoveredState(); ready = true;
        } catch (Exception failure) { throw recovery(failure); }
        finally { busy = false; }
    }

    private Receipt reject(Request request, Reason reason) throws IOException {
        Entry entry = Entry.rejected(request, reason);
        try { journal.create(entry); }
        catch (IOException failure) {
            Optional<Entry> actual = resolve(request.id(), failure);
            if (actual.isEmpty()) throw new IOException("Construction refusal could not be stored", failure);
            if (!actual.get().equals(entry)) throw recovery(failure);
        }
        return entry.receipt();
    }

    private Entry rollback(Entry prepared, Host host, Reason reason) throws RecoveryRequired {
        Intent intent = prepared.intent();
        try {
            // Verify every participant before restoring any. Unknown external edits are not ours to repair.
            long revision = host.revision();
            if (revision != intent.request().baseRevision() && revision != intent.resultRevision())
                throw new IOException("Foreign revision during recovery");
            for (Change change : intent.changes()) {
                Value actual = host.read(change.resource());
                if (!change.before().equals(actual) && !change.after().equals(actual))
                    throw new IOException("Foreign participant value during recovery: " + change.resource());
            }
            for (Change change : intent.changes()) host.write(change.resource(), change.before());
            host.flush(resources(intent)); requireState(intent, host, false);
            try { return journal.decide(prepared.request().id(), Phase.ABORTED, reason); }
            catch (IOException failure) {
                Entry actual = resolve(prepared.request().id(), failure).orElseThrow(() -> new IOException("Intent disappeared"));
                if (!actual.equals(prepared.finish(Phase.ABORTED, reason))) throw failure;
                return actual;
            }
        } catch (Exception failure) { throw recovery(failure); }
    }

    private Optional<Entry> resolve(UUID id, Exception original) throws RecoveryRequired {
        try { return journal.verify(id); }
        catch (Exception failure) { if (failure != original) failure.addSuppressed(original); throw recovery(failure); }
    }
    private static boolean matches(Intent intent, Host host, boolean after) throws Exception {
        for (Change change : intent.changes())
            if (!(after ? change.after() : change.before()).equals(host.read(change.resource()))) return false;
        return true;
    }
    private static void requireState(Intent intent, Host host, boolean after) throws Exception {
        if (host.revision() != (after ? intent.resultRevision() : intent.request().baseRevision()) || !matches(intent, host, after))
            throw new IOException("Participant image/revision mismatch");
    }
    private static List<String> resources(Intent intent) { return intent.changes().stream().map(Change::resource).toList(); }
    private RecoveryRequired recovery(Throwable cause) { ready = false; return new RecoveryRequired(cause); }
    private void enter(Host host) throws IOException {
        if (busy) throw new IOException("Reentrant construction transaction refused");
        host.checkAccess(); busy = true;
    }
}
