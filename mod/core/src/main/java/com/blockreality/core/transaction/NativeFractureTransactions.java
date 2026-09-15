package com.blockreality.core.transaction;

import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiFractureReceipt;
import com.blockreality.core.engine.InProcessEngine;
import java.io.IOException;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

/** The existing durable coordinator owns decisions; a native candidate is a publication resource. */
public final class NativeFractureTransactions implements AutoCloseable {
    @FunctionalInterface public interface EngineFactory {
        /** A fresh, private session with the complete accepted vocabulary; no world has been published. */
        InProcessEngine open() throws Exception;
    }
    public interface Host extends AtomicConstructionCoordinator.Host {
        /** Complete current source, including artifact identity and the revision participant. */
        BsiFracture.World snapshot() throws Exception;
        /**
         * Pure server capture of actual block/ledger/revision before/after images. No game publication,
         * native calls, consumed IDs or writes. After applying these images snapshot() must equal
         * remaining, including ground observations. The coordinator retains its existing limits.
         */
        Intent prepare(Request request, BsiFractureReceipt nativeReceipt, BsiFracture.World remaining) throws Exception;
    }

    private final AtomicConstructionCoordinator coordinator;
    private final EngineFactory factory;
    private final UUID domain;
    private InProcessEngine engine;
    private boolean busy, closed;

    public NativeFractureTransactions(TransactionJournal journal, EngineFactory factory) {
        coordinator=new AtomicConstructionCoordinator(journal); this.factory=Objects.requireNonNull(factory); domain=journal.domain();
    }
    public synchronized boolean ready() {
        return !closed && coordinator.ready() && engine!=null && engine.status()==InProcessEngine.Status.READY;
    }

    /** Recovery publishes current durable state, never a stored native token or an old committed image. */
    public synchronized void recover(Host host) throws IOException {
        enter(host);
        try { coordinator.recover(new Publication(host)); }
        finally { busy=false; }
    }

    public synchronized Receipt execute(Request request, BsiFracture.Options options, Host host) throws IOException {
        enter(host); Publication publication=new Publication(host);
        try {
            return coordinator.execute(request, bound -> {
                BsiFracture.World before=host.snapshot();
                if (!before.stamp().domain().equals(bound.domain()) || before.stamp().revision()!=bound.baseRevision()
                        || !before.planHash(options).equals(bound.planHash())) throw new AtomicConstructionCoordinator.ValidationRefused();
                requireEngine();
                if (!before.equals(engine.identifiedWorld()) && !engine.declareIdentifiedWorld(before))
                    throw new IOException("Native world synchronization refused");
                BsiFractureReceipt candidate=engine.prepareFracture(bound.id(),options);
                if (candidate==null) throw new IOException("Native fracture preparation refused");
                publication.candidate=candidate; publication.before=before; publication.after=before.remaining(candidate);
                if (candidate.fragments().size()>MAX_PIECES) throw new AtomicConstructionCoordinator.ValidationRefused();
                Intent images=host.prepare(bound,candidate,publication.after);
                if (!images.request().equals(bound)) throw new IllegalArgumentException("Fracture host changed request binding");
                var changes=new ArrayList<>(images.changes()); String prefix=proofPrefix(bound.id());
                for (Change change:changes) if (change.resource().startsWith("fracture/"))
                    throw new IllegalArgumentException("Fracture proof namespace is reserved");
                byte[] frame=candidate.frame();
                for (int offset=0,part=0;offset<frame.length;offset+=MAX_VALUE_BYTES,part++) {
                    int end=(int)Math.min((long)frame.length,(long)offset+MAX_VALUE_BYTES);
                    changes.add(new Change(prefix+part,Value.missing(),Value.of(Arrays.copyOfRange(frame,offset,end))));
                }
                // Intent performs the existing total record/resource admission before PREPARED.
                return new Intent(bound,changes,images.created(),images.retired());
            },publication);
        } finally {
            // Discard only revokes a native candidate. It cannot undo a durable COMMITTED decision.
            // A failed finish may have committed natively; recovery re-declares durable current state.
            try {
                if (publication.candidate!=null && engine!=null && engine.status()==InProcessEngine.Status.READY)
                    engine.finishFracture(publication.candidate,false);
            } finally { busy=false; }
        }
    }

    public static String proofPrefix(UUID request) { return "fracture/"+request+"/receipt/"; }
    private void requireEngine() throws IOException {
        if (engine==null || engine.status()!=InProcessEngine.Status.READY || engine.vocabulary()==null)
            throw new IOException("Native fracture session requires recovery");
    }
    private void synchronizeRecovered(Host host) throws Exception {
        if (engine!=null) { engine.close(); engine=null; }
        InProcessEngine next=factory.open();
        if (next==null) throw new IOException("Native fracture session unavailable");
        boolean accepted=false;
        try {
            BsiFracture.World current=host.snapshot();
            if (!current.stamp().domain().equals(domain) || current.stamp().revision()!=host.revision() || !next.declareIdentifiedWorld(current))
                throw new IOException("Native recovery world refused");
            engine=next; accepted=true;
        } finally { if (!accepted) next.close(); }
    }

    private final class Publication implements AtomicConstructionCoordinator.Host {
        final Host host;
        BsiFractureReceipt candidate;
        BsiFracture.World before,after;
        Publication(Host host) { this.host=Objects.requireNonNull(host); }
        @Override public void checkAccess() { host.checkAccess(); }
        @Override public long revision() { return host.revision(); }
        @Override public Value read(String resource) throws Exception { return host.read(resource); }
        @Override public void checkpoint(List<String> resources) throws Exception {
            if (!host.snapshot().equals(before)) throw new IOException("Fracture source changed before checkpoint");
            host.checkpoint(resources);
            if (!host.snapshot().equals(before)) throw new IOException("Fracture source changed during checkpoint");
        }
        @Override public void write(String resource, Value value) throws Exception { host.write(resource,value); }
        @Override public void flush(List<String> resources) throws Exception {
            host.flush(resources);
            if (candidate!=null) {
                var current=host.snapshot(); var expected=host.revision()==before.stamp().revision()?before:after;
                if (current.stamp().revision()!=host.revision() || !current.equals(expected))
                    throw new IOException("Persisted fracture world differs from native candidate");
            }
        }
        @Override public void publish(Receipt decision) throws Exception {
            if (candidate==null || decision.phase()!=Phase.COMMITTED || !decision.request().id().equals(candidate.request())
                    || decision.revision()!=candidate.after().revision() || !host.snapshot().equals(after))
                throw new IOException("Fracture publication binding differs");
            var finished=engine.finishFracture(candidate,true);
            if (finished!=BsiFracture.Finish.COMMITTED && finished!=BsiFracture.Finish.REPLAYED)
                throw new IOException("Durable fracture requires native recovery");
            host.publish(decision);
        }
        @Override public void publishRecoveredState() throws Exception {
            synchronizeRecovered(host); host.publishRecoveredState();
        }
    }
    private void enter(Host host) throws IOException {
        if (closed || busy) throw new IOException("Closed or reentrant fracture transaction");
        host.checkAccess(); busy=true;
    }
    @Override public synchronized void close() {
        if (busy) throw new IllegalStateException("Fracture transaction in progress");
        closed=true; if (engine!=null) { engine.close(); engine=null; }
    }
}
