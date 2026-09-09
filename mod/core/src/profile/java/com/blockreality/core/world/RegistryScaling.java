package com.blockreality.core.world;

import com.blockreality.api.geom.BlockKey;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** Opt-in measurement driver, absent from production and test jars. No engine needed. */
public final class RegistryScaling {
    private static final int WARMUP = 5, MEASURED = 20;
    private static final ConstructionDeclaration CONCRETE = new ConstructionDeclaration("concrete", "concrete_rect_400x600", 0);
    private static final ConstructionDeclaration STEEL = new ConstructionDeclaration("steel", "steel_rect_200x400", 0);
    private static final com.sun.management.ThreadMXBean ALLOC = allocationBean();
    private static final List<String> stages = new ArrayList<>();
    private static volatile Object blackhole;

    private RegistryScaling() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("output-new-file fork-number");
        var path = Path.of(args[0]); int fork = Integer.parseInt(args[1]);
        try (var out = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            emit(out, "{\"kind\":\"runtime\",\"fork\":" + fork + ",\"java\":\"" + System.getProperty("java.runtime.version")
                    + "\",\"vm\":\"" + System.getProperty("java.vm.name") + "\",\"os\":\"" + System.getProperty("os.name")
                    + "\",\"kernel\":\"" + System.getProperty("os.version") + "\",\"processors\":" + Runtime.getRuntime().availableProcessors()
                    + ",\"heap_max\":" + Runtime.getRuntime().maxMemory() + ",\"allocation_supported\":" + (ALLOC != null) + "}");
            for (String fixture : List.of("D4096", "D32768", "D131072", "F131072")) {
                for (String pattern : List.of("ONE", "BURST64")) run(out, fork, fixture, pattern);
            }
            emit(out, "{\"kind\":\"complete\",\"fork\":" + fork + "}");
        }
    }
    private static void run(java.io.BufferedWriter out, int fork, String fixture, String pattern) throws Exception {
        boolean sparse = fixture.startsWith("F");
        var declaration = sparse ? STEEL : CONCRETE;
        var positions = new ArrayList<BlockKey>();
        int width = fixture.equals("D4096") ? 32 : fixture.equals("D32768") ? 64 : 128;
        int height = fixture.equals("D4096") ? 4 : 8;
        for (int x = 0; x < width; x++) {
            if (sparse) { for (int row = 0; row < 1024; row++) positions.add(new BlockKey(4096+x,128,2*row)); }
            else for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) positions.add(new BlockKey(4096+x,128+y,z));
        }
        // Sparse burst is the first 64 canonical positions: the first cell of 64 beams.
        UUID namespace = UUID.nameUUIDFromBytes((fixture+":"+pattern+":"+fork).getBytes(StandardCharsets.UTF_8));
        var ledger = new ConstructionLedger(new ConstructionLedger.Graph(namespace, 1, Map.of()));
        var coverage = new WorldCellIndex(); stages.clear();
        emit(out, "{\"kind\":\"start\",\"fork\":"+fork+",\"fixture\":\""+fixture+"\",\"pattern\":\""+pattern+"\"}");
        measure("populate", () -> { for (var p : positions) { check(ledger.observe(p,declaration), "populate declaration"); check(coverage.add(p),"populate coverage"); } return ledger; });
        var initial = measure("capture", ledger::work);
        var done = measure("reconcile", () -> ConstructionLedger.reconcile(initial));
        measure("publish", () -> ledger.publish(done));
        check(done.failure().isEmpty() && ledger.ready(), "initial ready: " + done.failure());
        check(ledger.graph().activeCount() == (sparse ? 1024 : 1), "initial grouping");
        emit(out, row("initial",fork,fixture,pattern,-1,ledger,0,"",false));
        ConstructionLedger.Work previous = null;
        ConstructionDeclaration previousFirst = null;
        Set<BlockKey> previousDestroyed = null;
        var first = positions.get(0);
        for (int iteration = 0; iteration < WARMUP + MEASURED; iteration++) {
            stages.clear(); int i = iteration;
            measure("edit", () -> {
                if (pattern.equals("ONE")) {
                    check(ledger.observe(first,new ConstructionDeclaration(declaration.material(),declaration.section(), i % 2 == 0 ? 2 : 0)),"single edit");
                } else {
                    for (int n = 0; n < 64; n++) { var p = positions.get(n); check(ledger.remove(p),"remove declaration"); check(coverage.remove(p),"remove coverage");
                        check(ledger.observe(p,declaration),"replace declaration"); check(coverage.add(p),"replace coverage"); }
                }
                return ledger.epoch();
            });
            if (previous != null) {
                check(previous.cells().size() == positions.size() && previous.cells().get(first).equals(previousFirst), "old cells changed");
                check(previous.destroyed().equals(previousDestroyed), "old destruction changed");
            }
            var work = measure("capture", ledger::work);
            var completion = measure("reconcile", () -> ConstructionLedger.reconcile(work));
            check(completion.failure().isEmpty(), completion.failure());
            check(measure("publish", () -> ledger.publish(completion)), "publication");
            long expectedEpoch = positions.size() + (iteration+1L)*(pattern.equals("ONE") ? 1 : 128);
            check(ledger.ready() && ledger.epoch() == expectedEpoch && ledger.completedEpoch() == expectedEpoch, "epoch");
            check(ledger.cellCount() == positions.size() && ledger.graph().owners().size() == positions.size(), "cell count");
            boolean split = sparse && pattern.equals("ONE") && iteration % 2 == 0;
            check(ledger.graph().activeCount() == (sparse ? 1024 : 1) + (split ? 1 : 0), "active count");
            int expectedRecords = sparse && pattern.equals("ONE") ? 1024+2*((iteration+2)/2)+(iteration+1)/2 : sparse ? 1024 : 1;
            check(ledger.graph().records().size() == expectedRecords, "lineage record count");
            if (!sparse || pattern.equals("BURST64")) check(ledger.graph().owners().get(first) == 1, "retained ID");
            if (sparse) check(ledger.graph().owners().get(new BlockKey(4096,128,2046)) == 1024, "unrelated ID");
            var observed = measure("coverage_snapshot", coverage::cells);
            check(observed.equals(positions), "canonical coverage");
            var coverageBytes = measure("coverage_encode", coverage::encode);
            var encoded = measure("objects_encode", ledger::encode);
            var restored = measure("objects_decode", () -> ConstructionLedger.decode(encoded));
            check(Arrays.equals(encoded,restored.encode()), "canonical round trip");
            check(restored.ready(),"restored ready");
            var digest = MessageDigest.getInstance("SHA-256"); digest.update(coverageBytes); digest.update(encoded);
            String hash = HexFormat.of().formatHex(digest.digest());
            emit(out, row(iteration < WARMUP ? "warmup" : "sample",fork,fixture,pattern,iteration,ledger,encoded.length,hash,
                    pattern.equals("BURST64") || iteration == 0));
            previous = work; previousFirst = work.cells().get(first); previousDestroyed = new HashSet<>(work.destroyed());
        }
    }
    private static String row(String kind, int fork, String fixture, String pattern, int iteration,
                              ConstructionLedger ledger, int bytes, String hash, boolean cold) {
        long gcCount=0,gcMs=0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) { gcCount+=gc.getCollectionCount(); gcMs+=gc.getCollectionTime(); }
        return "{\"kind\":\""+kind+"\",\"fork\":"+fork+",\"fixture\":\""+fixture+"\",\"pattern\":\""+pattern
                +"\",\"iteration\":"+iteration+",\"cells\":"+ledger.cellCount()+",\"records\":"+ledger.graph().records().size()
                +",\"bytes\":"+bytes+",\"digest\":\""+hash+"\",\"coverage_cold\":"+cold+",\"gc_count\":"+gcCount+",\"gc_ms\":"+gcMs
                +",\"heap_used\":"+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())+",\"stages\":["+String.join(",",stages)+"]}";
    }
    private static <T> T measure(String name, Supplier<T> action) {
        long allocated = allocated(), start = System.nanoTime();
        T result = action.get(); long duration = System.nanoTime()-start, after = allocated(); blackhole = result;
        stages.add("{\"name\":\""+name+"\",\"ns\":"+duration+",\"allocated\":"+(allocated < 0 || after < 0 ? "null" : Long.toString(after-allocated))+"}");
        return result;
    }
    private static com.sun.management.ThreadMXBean allocationBean() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean b) || !b.isThreadAllocatedMemorySupported()) return null;
        if (!b.isThreadAllocatedMemoryEnabled()) b.setThreadAllocatedMemoryEnabled(true);
        return b;
    }
    private static long allocated() { return ALLOC == null ? -1 : ALLOC.getThreadAllocatedBytes(Thread.currentThread().getId()); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void emit(java.io.BufferedWriter out, String row) throws java.io.IOException { out.write(row); out.newLine(); out.flush(); }
}
