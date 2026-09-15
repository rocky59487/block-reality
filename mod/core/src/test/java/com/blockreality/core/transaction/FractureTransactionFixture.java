package com.blockreality.core.transaction;

import com.blockreality.core.bsi.*;
import java.io.IOException;
import java.nio.*;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

/** File/memory participant fixture, never a Forge adapter or a physics model. */
class FractureTransactionFixture implements NativeFractureTransactions.Host {
    final FractureCases.Case fixture;
    final Map<String,Value> live=new TreeMap<>(),durable=new TreeMap<>();
    BsiFracture.World visible;
    BsiFractureReceipt prepared;
    int prepares,writes,flushes,publishes,baselines;
    int failWrite;
    boolean failFlush,failPublish,failCheckpoint,omitWorldImage;
    FractureTransactionFixture(FractureCases.Case fixture) {
        this.fixture=fixture; live.put("world/source",worldValue(fixture.world()));
        live.put("revision",revisionValue(fixture.world().stamp().revision())); durable.putAll(live);
    }
    static Request request(FractureCases.Case c) {
        return new Request(c.request(),new UUID(2,2),new UUID(3,3),c.world().stamp().domain(),c.world().stamp().revision(),c.world().planHash(c.options()));
    }
    static Value revisionValue(long revision) { return Value.of(ByteBuffer.allocate(8).putLong(revision).array()); }
    static Value worldValue(BsiFracture.World w) {
        byte[] payload=w.payload(); var b=ByteBuffer.allocate(48+payload.length);
        b.putLong(w.stamp().domain().getMostSignificantBits()).putLong(w.stamp().domain().getLeastSignificantBits()).putLong(w.stamp().revision());
        b.putLong(w.artifactNamespace().getMostSignificantBits()).putLong(w.artifactNamespace().getLeastSignificantBits());
        b.putInt(w.blocks().size()).putInt(w.owners().size()).put(payload); return Value.of(b.array());
    }
    static BsiFracture.World readWorld(Value value) {
        var b=ByteBuffer.wrap(value.bytes()); var domain=new UUID(b.getLong(),b.getLong()); long revision=b.getLong();
        var ns=new UUID(b.getLong(),b.getLong()); int blocks=b.getInt(),owners=b.getInt(); byte[] payload=new byte[b.remaining()]; b.get(payload);
        return FractureCases.world(new BsiFracture.Stamp(domain,revision),ns,blocks,owners,payload);
    }
    @Override public void checkAccess() { }
    @Override public long revision() { return ByteBuffer.wrap(read("revision").bytes()).getLong(); }
    @Override public Value read(String resource) { return live.getOrDefault(resource,Value.missing()); }
    @Override public BsiFracture.World snapshot() { return readWorld(read("world/source")); }
    @Override public Intent prepare(Request request,BsiFractureReceipt receipt,BsiFracture.World remaining) {
        prepares++; prepared=receipt;
        var changes=new ArrayList<Change>();
        if (!omitWorldImage) changes.add(new Change("world/source",read("world/source"),worldValue(remaining)));
        changes.add(new Change("revision",read("revision"),revisionValue(remaining.stamp().revision())));
        return new Intent(request,changes,List.of(),List.of());
    }
    @Override public void checkpoint(List<String> resources) throws IOException {
        if (failCheckpoint) throw new IOException("Checkpoint fault");
        for (String r:resources) if (!read(r).equals(durable.getOrDefault(r,Value.missing()))) throw new IOException("Baseline was not durable");
    }
    @Override public void write(String resource,Value value) throws IOException {
        if (++writes==failWrite) throw new IOException("Participant write fault"); live.put(resource,value);
    }
    @Override public void flush(List<String> resources) throws IOException {
        flushes++; for (String r:resources) durable.put(r,read(r));
        if (failFlush) { failFlush=false; throw new IOException("Participant flush fault"); }
    }
    @Override public void publish(Receipt receipt) throws IOException {
        publishes++; if (failPublish) throw new IOException("Publication fault"); visible=snapshot();
    }
    @Override public void publishRecoveredState() throws IOException { baselines++; visible=snapshot(); }
}
