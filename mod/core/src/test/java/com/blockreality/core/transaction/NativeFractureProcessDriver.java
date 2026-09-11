package com.blockreality.core.transaction;

import com.blockreality.core.bsi.*;
import com.blockreality.core.engine.InProcessEngine;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static java.nio.file.StandardOpenOption.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

/** Actual native sessions plus durable journal/file participants, in independently restarted JVMs. */
public final class NativeFractureProcessDriver {
    private NativeFractureProcessDriver() { }
    public static void main(String[] args) throws Exception {
        if (args.length!=4) throw new IllegalArgumentException("library mode scenario owned-directory");
        Path library=Path.of(args[0]),root=Path.of(args[3]); String mode=args[1],scenario=args[2];
        var c=FractureCases.named("L"); var marker=root.resolve("NATIVE_FRACTURE_PROCESS_OWNED");
        if (mode.equals("crash")) Files.writeString(marker,"native-fracture-process-v1",CREATE_NEW);
        else if (!Files.readString(marker).equals("native-fracture-process-v1")) throw new IOException("Ownership marker required");
        var nativeRef=new AtomicReference<InProcessEngine>();
        var host=new FileHost(c,root,mode.equals("crash")?scenario:"",nativeRef);
        if (mode.equals("crash")) host.initialize(); else host.load();
        try (var journal=new FileTransactionJournal(root.resolve("journal"),c.world().stamp().domain(),(stage,entry) -> {
            if (!mode.equals("crash")) return;
            String phase=entry.phase()==Phase.PREPARED?"prepare":entry.phase()==Phase.COMMITTED?"commit":"abort";
            haltWhen(scenario,phase+"-"+stage.name().toLowerCase(Locale.ROOT));
        }); var bridge=new NativeFractureTransactions(journal,() -> { var e=c.open(library); nativeRef.set(e); return e; })) {
            bridge.recover(host);
            if (mode.equals("crash")) {
                bridge.execute(FractureTransactionFixture.request(c),c.options(),host);
                throw new AssertionError("Crash point not reached: "+scenario);
            }
            boolean absent=Set.of("native-prepared","checkpoint","prepare-temp_forced").contains(scenario);
            boolean committed=scenario.startsWith("commit-")&&!scenario.endsWith("temp_forced") || Set.of("native-finished","published").contains(scenario);
            var expected=committed?c.world().remaining(c.receipt()):c.world();
            if (mode.equals("later")) expected=laterWorld(c,expected);
            equal(expected,host.snapshot(),"durable source"); equal(expected,nativeRef.get().identifiedWorld(),"fresh native source");
            equal(expected,FractureTransactionFixture.readWorld(readValue(root.resolve("visible.value"))),"visible source");
            if (!bridge.ready() || journal.pending().isPresent()) throw new AssertionError("Recovery not ready");
            var record=journal.read(c.request());
            if (absent) { if (record.isPresent()) throw new AssertionError("Non-durable candidate became a decision"); }
            else {
                equal(committed?Phase.COMMITTED:Phase.ABORTED,record.orElseThrow().phase(),"terminal decision");
                var result=record.orElseThrow().receipt(); int writes=host.writes;
                for (int i=0;i<100;i++) equal(result,bridge.execute(FractureTransactionFixture.request(c),c.options(),host),"replay "+i);
                if (host.prepares!=0 || host.writes!=writes || host.publishes!=0) throw new AssertionError("Restart replay repeated fracture side effects");
                if (committed) {
                    byte[] frame=host.read(NativeFractureTransactions.proofPrefix(c.request())+"0").bytes();
                    if (!Arrays.equals(c.payload(),BsiFrame.decode(frame,frame.length).payload())) throw new AssertionError("Complete native receipt changed");
                }
            }
            String report="{\"scenario\":\""+scenario+"\",\"mode\":\""+mode+"\",\"phase\":\""+record.map(e -> e.phase().name()).orElse("ABSENT")
                    +"\",\"revision\":"+host.revision()+",\"replays\":"+(absent?0:100)+",\"directory_sync\":"+journal.directorySyncAvailable()+",\"status\":\"PASS\"}\n";
            Files.writeString(root.resolve(mode+".json"),report,CREATE_NEW); System.out.print(report);
            if (mode.equals("recover")) {
                // Legitimate subsequent server edit: a second independent recovery must retain it.
                var later=laterWorld(c,expected); host.live.put("world/source",FractureTransactionFixture.worldValue(later));
                host.live.put("revision",FractureTransactionFixture.revisionValue(later.stamp().revision()));
                host.flush(List.of("world/source","revision"));
            }
        }
    }
    private static BsiFracture.World laterWorld(FractureCases.Case c,BsiFracture.World current) {
        return new BsiFracture.World(new BsiFracture.Stamp(current.stamp().domain(),current.stamp().revision()+1),current.artifactNamespace(),c.world().blocks(),c.world().owners());
    }
    private static void equal(Object expected,Object actual,String what) {
        if (!expected.equals(actual)) throw new AssertionError(what+": expected "+expected+", got "+actual);
    }
    private static void haltWhen(String scenario,String point) {
        if (scenario.equals(point)) { System.out.println("JVM halt at "+point); System.out.flush(); Runtime.getRuntime().halt(73); }
    }
    private static final class FileHost extends FractureTransactionFixture {
        final Path root; final String scenario; final AtomicReference<InProcessEngine> nativeRef;
        int saved,flushCalls;
        FileHost(FractureCases.Case c,Path root,String scenario,AtomicReference<InProcessEngine> nativeRef) {
            super(c); this.root=root; this.scenario=scenario; this.nativeRef=nativeRef;
        }
        Path file(String key) { return root.resolve("participant-"+Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8))+".value"); }
        void initialize() throws IOException { for (var entry:live.entrySet()) save(file(entry.getKey()),entry.getValue()); }
        void load() throws IOException {
            live.clear(); durable.clear();
            try (var files=Files.list(root)) {
                for (var path:files.filter(p -> p.getFileName().toString().startsWith("participant-")).toList()) {
                    String name=path.getFileName().toString(); String key=new String(Base64.getUrlDecoder().decode(name.substring(12,name.length()-6)),StandardCharsets.UTF_8);
                    live.put(key,readValue(path));
                }
            }
            durable.putAll(live);
        }
        @Override public Intent prepare(Request request,BsiFractureReceipt receipt,BsiFracture.World remaining) {
            var intent=super.prepare(request,receipt,remaining); haltWhen(scenario,"native-prepared"); return intent;
        }
        @Override public void checkpoint(List<String> resources) throws IOException { super.checkpoint(resources); haltWhen(scenario,"checkpoint"); }
        @Override public void write(String resource,Value value) throws IOException {
            super.write(resource,value); haltWhen(scenario,"write-"+writes);
            if (writes>3) haltWhen(scenario,"rollback-write-"+(writes-3));
        }
        @Override public void flush(List<String> resources) throws IOException {
            flushCalls++;
            for (String r:resources) {
                save(file(r),read(r)); durable.put(r,read(r)); haltWhen(scenario,"flush-"+(++saved));
                if (saved>3) haltWhen(scenario,"rollback-flush-"+(saved-3));
            }
            if (flushCalls==1 && (scenario.startsWith("rollback-") || scenario.startsWith("abort-"))) throw new IOException("Injected participant failure after durable writes");
        }
        @Override public void publish(Receipt receipt) throws IOException {
            equal(snapshot(),nativeRef.get().identifiedWorld(),"native must commit before game publication");
            haltWhen(scenario,"native-finished"); super.publish(receipt); save(root.resolve("visible.value"),worldValue(visible)); haltWhen(scenario,"published");
        }
        @Override public void publishRecoveredState() throws IOException {
            equal(snapshot(),nativeRef.get().identifiedWorld(),"recovery must synchronize native first");
            super.publishRecoveredState(); save(root.resolve("visible.value"),worldValue(visible));
        }
    }
    private static void save(Path path,Value value) throws IOException {
        try (var out=FileChannel.open(path,CREATE,TRUNCATE_EXISTING,WRITE)) {
            var b=ByteBuffer.allocate(4+Math.max(0,value.size())); value.put(b); b.flip(); while (b.hasRemaining()) out.write(b); out.force(true);
        }
    }
    private static Value readValue(Path path) throws IOException {
        var b=ByteBuffer.wrap(Files.readAllBytes(path)); int n=b.getInt();
        if (n==-1&&!b.hasRemaining()) return Value.missing();
        if (n<0||n!=b.remaining()) throw new IOException("Invalid participant image"); byte[] bytes=new byte[n]; b.get(bytes); return Value.of(bytes);
    }
}
