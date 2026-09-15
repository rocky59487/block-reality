package com.blockreality.core.engine;

import com.blockreality.api.WorldRevision;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiFrame;
import com.blockreality.core.bsi.BsiHeaders;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.json.JsonValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Explicit native gate: absent library, corpus or result is a failure, never SKIP. */
public final class PhysicalGravityGate {
    private static int checks;
    private static void check(String label, boolean ok) {
        checks++;
        System.out.println("PGN-JNA " + label + ": " + (ok ? "PASS" : "FAIL"));
        if (!ok) throw new AssertionError(label);
    }
    private static boolean near(double actual, double expected) {
        return Double.isFinite(actual) && Math.abs(actual-expected) <= 2e-10*Math.max(1,Math.abs(expected));
    }
    private static BsiResponse solve(InProcessEngine engine, BsiHeaders.MassModel mode) {
        var reply=engine.solve(true,new double[]{2,-3,5},List.of(),4,List.of("members"),null,null,mode);
        check("wrapper reply",reply!=null && !reply.isError());
        return reply;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3) throw new IllegalArgumentException("library corpus fresh-output required");
        var library=Path.of(args[0]); var corpus=Path.of(args[1]); var out=Path.of(args[2]);
        Files.createDirectory(out);
        try(var files=Files.list(corpus)) {
            check("66 native fixtures",files.filter(p->p.getFileName().toString().startsWith("request-")).count()==66);
        }
        for(int repeat=0;repeat<3;repeat++) {
            BsiNative session=null;
            try {
                for(int i=0;i<66;i++) {
                    byte[] request=Files.readAllBytes(corpus.resolve("request-"+i+".frame"));
                    var decoded=BsiFrame.decode(request,request.length);
                    if("bsi.hello".equals(JsonValue.parse(decoded.header()).str("method",""))) {
                        if(session!=null) session.close();
                        session=BsiNative.open(library,"{}");
                    }
                    if(session==null) throw new AssertionError("missing hello");
                    byte[] reply=session.call(request);
                    Files.write(out.resolve(repeat+"-"+i+".frame"),reply);
                    check("frame "+repeat+" "+i,Arrays.equals(reply,Files.readAllBytes(corpus.resolve("response-"+i+".frame"))));
                }
            } finally { if(session!=null) session.close(); }
            // Exercise the production header builder and wrapper, not just frame replay.
            try(var engine=InProcessEngine.open(library,4)) {
                check("wrapper ready",engine.status()==InProcessEngine.Status.READY && engine.has("bsi.mass.physical"));
                check("game vocabulary",engine.declareVocabulary(GameVocabulary.declaration()));
                int steel=engine.vocabulary().materialId("steel");
                int sec=engine.vocabulary().sectionId("steel_rect_200x400");
                int ground=engine.vocabulary().materialId("ground_rigid");
                var blocks=new ArrayList<BsiRecords.Block>();
                blocks.add(BsiRecords.Block.of(0,-1,0,ground,-1,1));
                for(int y=0;y<5;y++) blocks.add(BsiRecords.Block.of(0,y,0,steel,sec,1));
                check("wrapper world",engine.declareWorld(7,blocks));
                var old=solve(engine,null); var physical=solve(engine,BsiHeaders.MassModel.PHYSICAL);
                var restored=solve(engine,null);
                check("wrapper legacy bytes",Arrays.equals(old.payload(),restored.payload()));
                double mu=628.; // 0.2 m * 0.4 m * 7850 kg/m3, independent product geometry.
                check("wrapper legacy mass",near(old.equilibrium().applied()[1],-3*4*mu));
                check("wrapper physical mass",near(physical.equilibrium().applied()[1],-3*5*mu));
                var cells=new ArrayList<GameWorldSnapshot.Cell>();
                for(int y=0;y<5;y++) cells.add(GameWorldSnapshot.Cell.of(new BlockKey(0,y,0),"steel","steel_rect_200x400",1));
                var snapshot=new GameWorldSnapshot(new WorldRevision(8),cells,List.of(new BlockKey(0,-1,0)),List.of());
                var analysis=engine.analyze(snapshot,4,BsiHeaders.Storage.F64,null);
                check("production analysis",analysis.ok() && analysis.members().size()==1);
                var member=analysis.members().get(0);
                check("production root force",near(member.endI().n(),4.5*mu*9.81));
                check("production length",member.lengthMm()==4000.);
            }
        }
        if(checks!=235) throw new AssertionError("PHYSICAL_GRAVITY_NATIVE A2 coverage count");
        System.out.println("PGN-JNA-SUITE ALL PASS (checks="+checks+" failures=0)");
    }
}
