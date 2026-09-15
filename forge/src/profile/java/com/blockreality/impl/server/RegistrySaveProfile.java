package com.blockreality.impl.server;

import com.blockreality.core.world.ConstructionDeclaration;
import com.blockreality.core.world.ConstructionLedger;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;

/** Opt-in adapter measurement with generated data only. Never a game command or jar entry. */
public final class RegistrySaveProfile {
    private static final List<String> FIXTURES = List.of("D4096","D32768","D131072");
    private static final ConstructionDeclaration X = new ConstructionDeclaration("concrete","concrete_rect_400x600",0);
    private static final com.sun.management.ThreadMXBean ALLOC = allocationBean();
    private static final List<String> STAGES = new ArrayList<>();
    private static volatile Object blackhole;

    private RegistrySaveProfile() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("out-directory seed-directory seed|fork-number");
        SharedConstants.tryDetectVersion();
        Path out = Path.of(args[0]).toAbsolutePath(), seeds = Path.of(args[1]).toAbsolutePath();
        if (args[2].equals("seed")) {
            Files.createDirectory(seeds);
            for (String fixture : FIXTURES) {
                var folder = Files.createDirectory(seeds.resolve(fixture));
                var data = WorldIndexData.fresh(); for (var p : positions(fixture)) data.observe(p,X);
                settle(data); data.save(file(folder).toFile()); check(!data.isDirty() && data.failure().isEmpty(),"seed save");
                System.out.println(fixture+" seed "+hash(Files.readAllBytes(file(folder))));
            }
            return;
        }
        int fork = Integer.parseInt(args[2]);
        try (var writer = Files.newBufferedWriter(out.resolve("fork-"+fork+".jsonl"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            emit(writer,"{\"kind\":\"runtime\",\"fork\":"+fork+",\"java\":\""+System.getProperty("java.runtime.version")
                    +"\",\"os\":\""+System.getProperty("os.name")+"\",\"kernel\":\""+System.getProperty("os.version")
                    +"\",\"heap_max\":"+Runtime.getRuntime().maxMemory()+",\"allocation_supported\":"+(ALLOC!=null)+"}");
            for (String fixture : FIXTURES) for (String pattern : List.of("READY","PENDING64")) {
                Path folder = Files.createDirectory(out.resolve("fork-"+fork+"-"+fixture+"-"+pattern));
                Path seed = file(seeds.resolve(fixture)); Files.copy(seed,file(folder));
                var data = open(folder); check(data.objectsReady() && !data.isDirty(),"ready seed");
                var positions = positions(fixture); var first = positions.get(0);
                long baseEpoch = data.objects().epoch(); var namespace = data.objects().graph().namespace();
                check(baseEpoch==positions.size() && data.objects().graph().activeCount()==1,"seed shape");
                var seedHash = hash(Files.readAllBytes(seed));
                emit(writer,"{\"kind\":\"start\",\"fork\":"+fork+",\"fixture\":\""+fixture+"\",\"pattern\":\""+pattern
                        +"\",\"namespace\":\""+namespace+"\",\"seed_sha256\":\""+seedHash+"\"}");
                for (int iteration=0;iteration<25;iteration++) {
                    boolean pending = pattern.equals("PENDING64");
                    if (pending) for (int i=0;i<64;i++) { data.remove(positions.get(i)); data.observe(positions.get(i),X); }
                    else { data.observe(first,new ConstructionDeclaration(X.material(),X.section(),iteration%2==0?2:0)); settle(data); }
                    long expectedEpoch = baseEpoch+(iteration+1L)*(pending?128:1);
                    check(data.isDirty() && data.objects().epoch()==expectedEpoch,"edited epoch/dirty");
                    STAGES.clear();
                    var tag = measure("tag_encode", () -> data.save(new CompoundTag()));
                    measure("file_save", () -> { data.save(file(folder).toFile()); return data.isDirty(); });
                    check(!data.isDirty() && data.failure().isEmpty(),"file save failed: "+data.failure());
                    var restored = measure("reopen", () -> open(folder));
                    check(restored.failure().isEmpty() && !restored.isDirty(),"reopen");
                    check(restored.objects().graph().namespace().equals(namespace) && restored.objects().epoch()==expectedEpoch,"restored identity/epoch");
                    var actual = restored.save(new CompoundTag());
                    check(Arrays.equals(tag.getByteArray("coverage"),actual.getByteArray("coverage")),"coverage bytes");
                    check(Arrays.equals(tag.getByteArray("objects"),actual.getByteArray("objects")),"object bytes");
                    check(restored.objects().pending()==pending && restored.objectsReady()!=pending,"pending/current status");
                    if (pending) {
                        var expectedDestroyed = new HashSet<com.blockreality.api.geom.BlockKey>();
                        for (int i=0;i<64;i++) expectedDestroyed.add(WorldIndexData.key(positions.get(i)));
                        check(restored.objects().work().destroyed().equals(expectedDestroyed),"saved destruction");
                        check(restored.objects().completedEpoch()==expectedEpoch-128,"saved prior epoch");
                        settle(restored); settle(data);
                    }
                    check(restored.objects().graph().activeCount()==1 && restored.objects().graph().owners().get(WorldIndexData.key(first))==1,"retained monolith");
                    try (var files = Files.list(folder)) { check(files.count()==1,"temporary file left behind"); }
                    var digest = MessageDigest.getInstance("SHA-256");digest.update(tag.getByteArray("coverage"));digest.update(tag.getByteArray("objects"));
                    long gcCount=0,gcMs=0;
                    for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) { gcCount+=gc.getCollectionCount();gcMs+=gc.getCollectionTime(); }
                    emit(writer,"{\"kind\":\""+(iteration<5?"warmup":"sample")+"\",\"fork\":"+fork+",\"fixture\":\""+fixture
                            +"\",\"pattern\":\""+pattern+"\",\"iteration\":"+iteration+",\"cells\":"+positions.size()
                            +",\"epoch\":"+expectedEpoch+",\"payload_sha256\":\""+HexFormat.of().formatHex(digest.digest())+"\",\"compressed_bytes\":"+Files.size(file(folder))
                            +",\"file_sha256\":\""+hash(Files.readAllBytes(file(folder)))+"\",\"gc_count\":"+gcCount+",\"gc_ms\":"+gcMs
                            +",\"heap_used\":"+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())+",\"stages\":["+String.join(",",STAGES)+"]}");
                }
                check(hash(Files.readAllBytes(seed)).equals(seedHash),"seed was modified");
            }
            emit(writer,"{\"kind\":\"complete\",\"fork\":"+fork+"}");
        }
    }
    private static List<BlockPos> positions(String fixture) {
        int width=fixture.equals("D4096")?32:fixture.equals("D32768")?64:128;
        int height=fixture.equals("D4096")?4:8;var result=new ArrayList<BlockPos>();
        for(int x=0;x<width;x++)for(int y=0;y<height;y++)for(int z=0;z<width;z++)result.add(new BlockPos(4096+x,128+y,z));
        return result;
    }
    private static Path file(Path folder) { return folder.resolve(WorldIndexData.NAME+".dat"); }
    private static WorldIndexData open(Path folder) { return WorldIndexData.open(new DimensionDataStorage(folder.toFile(),DataFixers.getDataFixer()),folder); }
    private static void settle(WorldIndexData data) {
        var completion = ConstructionLedger.reconcile(data.objects().work());
        check(completion.failure().isEmpty() && data.publishObjects(completion) && data.objectsReady(),"reconcile: "+completion.failure());
    }
    private static <T> T measure(String name,Supplier<T> action) {
        long allocation=allocated(),start=System.nanoTime();T result=action.get();long elapsed=System.nanoTime()-start,after=allocated();blackhole=result;
        STAGES.add("{\"name\":\""+name+"\",\"ns\":"+elapsed+",\"allocated\":"+(allocation<0||after<0?"null":Long.toString(after-allocation))+"}");return result;
    }
    private static com.sun.management.ThreadMXBean allocationBean() {
        var bean=ManagementFactory.getThreadMXBean();
        if(!(bean instanceof com.sun.management.ThreadMXBean b)||!b.isThreadAllocatedMemorySupported())return null;
        if(!b.isThreadAllocatedMemoryEnabled())b.setThreadAllocatedMemoryEnabled(true);return b;
    }
    private static long allocated(){return ALLOC==null?-1:ALLOC.getThreadAllocatedBytes(Thread.currentThread().getId());}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void emit(BufferedWriter writer,String row)throws java.io.IOException{writer.write(row);writer.newLine();writer.flush();}
}
