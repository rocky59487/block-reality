package com.blockreality.core.bsi;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.engine.InProcessEngine;
import com.blockreality.core.json.JsonValue;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Existing native oracle inputs and unchanged reply bytes, shared by consumer gates. */
public final class FractureCases {
    private FractureCases() { }
    public record Case(String name, String vocabulary, BsiFracture.World world, UUID request,
                       BsiFracture.Options options, String header, byte[] payload) {
        public BsiFractureReceipt receipt() {
            return BsiFractureReceipt.decode(response(header,payload),world,request,options,"nfw");
        }
        public boolean refused() { return response(header,payload).isError(); }
        public InProcessEngine open(Path library) {
            InProcessEngine e=InProcessEngine.open(library,4);
            if (e.status()!=InProcessEngine.Status.READY || !e.declareVocabulary(vocabulary)) {
                var why=e.disabledReason(); e.close(); throw new AssertionError("Native session/vocabulary: "+why);
            }
            return e;
        }
    }
    public static BsiResponse response(String header,byte[] payload) {
        return BsiResponse.of(new BsiFrame.Decoded(7,header,payload));
    }
    public static List<Case> all() {
        try (var in=Objects.requireNonNull(FractureCases.class.getResourceAsStream("/fracture/cases.jsonl"));
             var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
            return reader.lines().map(line -> {
                var row=JsonValue.parseStrict(line); var declaration=row.objField("world"); var identity=declaration.objField("identity");
                var stamp=new BsiFracture.Stamp(BsiFracture.uuid(identity.str("domain","")),identity.exactI64("revision"));
                var ns=BsiFracture.uuid(identity.str("artifactNamespace",""));
                var world=world(stamp,ns,(int)declaration.exactI64("blocks"),(int)identity.exactI64("owners"),HexFormat.of().parseHex(row.str("worldPayload","")));
                var body=JsonValue.parseStrict(row.str("prepare","")).objField("body"); var g=body.arr("gravity");
                var options=new BsiFracture.Options(g.get(0).asNum(Double.NaN),g.get(1).asNum(Double.NaN),g.get(2).asNum(Double.NaN),(int)body.exactI64("budget"),(int)body.exactI64("numThreads"));
                return new Case(row.str("name",""),row.str("vocab",""),world,BsiFracture.uuid(body.str("requestId","")),options,row.str("reply",""),HexFormat.of().parseHex(row.str("replyPayload","")));
            }).toList();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static Case named(String name) { return all().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(); }
    public static BsiFracture.World world(BsiFracture.Stamp stamp,UUID namespace,int nBlocks,int nOwners,byte[] payload) {
        var b=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN); var blocks=new ArrayList<BsiRecords.Block>(); var owners=new ArrayList<BsiFracture.Owner>();
        for (int i=0;i<nBlocks;i++) {
            int x=b.getInt(),y=b.getInt(),z=b.getInt(),mat=b.getInt(),section=b.getInt();
            int axis=Byte.toUnsignedInt(b.get()),joint=Byte.toUnsignedInt(b.get()),rot=Byte.toUnsignedInt(b.get());
            if (b.get()!=0) throw new AssertionError("Source reserved");
            blocks.add(new BsiRecords.Block(x,y,z,mat,section,axis,joint,rot,b.getDouble(),b.getDouble()));
        }
        for (int i=0;i<nOwners;i++) owners.add(new BsiFracture.Owner(new BlockKey(b.getInt(),b.getInt(),b.getInt()),b.getLong()));
        if (b.hasRemaining()) throw new AssertionError("Source trailing bytes");
        return new BsiFracture.World(stamp,namespace,blocks,owners);
    }
}
