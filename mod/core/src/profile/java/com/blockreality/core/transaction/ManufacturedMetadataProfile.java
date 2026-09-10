package com.blockreality.core.transaction;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.world.ConstructionDeclaration;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

/** Metadata-only measurements; no world, player, native solver or client is created. */
public final class ManufacturedMetadataProfile {
    private static final String OWNER = "block-reality-manufactured-metadata-profile-v1", DIMENSION = "minecraft:overworld";
    private static final int[] PIECES = {64,1024,4096};
    private static final int WARMUPS = 8, MEASURED = 40;
    private static final UUID ACTOR = new UUID(20,20), SESSION = new UUID(30,30);
    private static final ConstructionDeclaration STEEL = new ConstructionDeclaration("steel","steel_rect_200x400",0);
    private static final com.sun.management.ThreadMXBean ALLOCATION = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private ManufacturedMetadataProfile() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("mode fixture-root output-root");
        Path fixture = absolute(args[1]), output = absolute(args[2]);
        if (!ALLOCATION.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Thread allocation unavailable");
        ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        if (args[0].equals("seed")) { seed(fixture); return; }
        if (!args[0].equals("measure") || !Files.readString(fixture.resolve("OWNER")).strip().equals(OWNER)) throw new IllegalArgumentException("Owned fixture required");
        Files.createDirectory(output); Files.writeString(output.resolve("OWNER"),OWNER+"\n",StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("environment.txt"),System.getProperty("java.runtime.version")+"\n"+System.getProperty("os.name")+"\n"
                +System.getProperty("os.arch")+"\nprocessors="+Runtime.getRuntime().availableProcessors()+"\n",StandardOpenOption.CREATE_NEW);
        try (var csv = Files.newBufferedWriter(output.resolve("samples.tsv"),StandardOpenOption.CREATE_NEW)) {
            csv.write("phase\tpieces\tcells\titeration\twarmup\tnanoseconds\tallocated_bytes\tfingerprint\n");
            for (int count : PIECES) {
                Path original = fixture.resolve("pieces-"+count).resolve("journal");
                List<UUID> ids = Files.readAllLines(original.getParent().resolve("ids.txt")).stream().map(UUID::fromString).toList();
                require(ids.size() == count && new HashSet<>(ids).size() == count,"fixture IDs");
                String beforeHash = null, preparedHash = null, afterHash = null;
                for (int n = 0; n < WARMUPS+MEASURED; n++) {
                    long allocated = allocation(), started = System.nanoTime(); ManufacturedRegistry registry;
                    try (var journal = FileTransactionJournal.open(original)) { registry = ManufacturedRegistry.load(journal); }
                    long elapsed = System.nanoTime()-started, bytes = allocation()-allocated;
                    String fingerprint = verify(registry,ids,false);
                    if (beforeHash == null) beforeHash = fingerprint; else require(beforeHash.equals(fingerprint),"reopen content changed");
                    row(csv,"reopen",count,n,elapsed,bytes,fingerprint);
                }
                try (var journal = FileTransactionJournal.open(original)) {
                    var registry = ManufacturedRegistry.load(journal);
                    Request request = editRequest(registry);
                    for (int n = 0; n < WARMUPS+MEASURED; n++) {
                        long allocated = allocation(), started = System.nanoTime();
                        var prepared = registry.prepareEdit(request,DIMENSION,Set.of(new BlockKey(0,80,0)),Set.of());
                        long elapsed = System.nanoTime()-started, bytes = allocation()-allocated;
                        String fingerprint = hash(TransactionCodec.encode(Entry.prepared(prepared.withParticipants(List.of()))));
                        if (preparedHash == null) preparedHash = fingerprint; else require(preparedHash.equals(fingerprint),"private preparation changed");
                        require(beforeHash.equals(verify(registry,ids,false)),"preparation published metadata");
                        row(csv,"prepare-edit",count,n,elapsed,bytes,fingerprint);
                    }
                }
                Path copies = Files.createDirectory(output.resolve("pieces-"+count));
                for (int n = 0; n < WARMUPS+MEASURED; n++) {
                    Path copy = Files.createDirectory(copies.resolve("commit-"+n));
                    try (var files = Files.newDirectoryStream(original)) {
                        for (Path file : files) {
                            require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"non-file fixture");
                            if (!file.getFileName().toString().equals(".owner.lock")) Files.copy(file,copy.resolve(file.getFileName()));
                        }
                    }
                    try (var journal = FileTransactionJournal.open(copy)) {
                        var registry = ManufacturedRegistry.load(journal); Request request = editRequest(registry);
                        var intent = registry.prepareEdit(request,DIMENSION,Set.of(new BlockKey(0,80,0)),Set.of()).withParticipants(List.of());
                        require(preparedHash.equals(hash(TransactionCodec.encode(Entry.prepared(intent)))),"copied before-image changed");
                        long allocated = allocation(), started = System.nanoTime();
                        journal.create(Entry.prepared(intent)); journal.decide(request.id(),Phase.COMMITTED,Reason.NONE);
                        registry.applyCommitted(journal,request.id());
                        long elapsed = System.nanoTime()-started, bytes = allocation()-allocated;
                        String fingerprint = verify(registry,ids,true);
                        if (afterHash == null) afterHash = fingerprint; else require(afterHash.equals(fingerprint),"committed content changed");
                        require(journal.read(request.id()).orElseThrow().phase() == Phase.COMMITTED,"missing committed decision");
                        row(csv,"commit-metadata",count,n,elapsed,bytes,fingerprint);
                    }
                }
                try (var journal = FileTransactionJournal.open(original)) {
                    require(beforeHash.equals(verify(ManufacturedRegistry.load(journal),ids,false)),"original fixture modified");
                }
                System.out.println("Recorded "+count+" pieces: all144 iterations verified; no FPS claim");
            }
        }
    }
    private static void seed(Path fixture) throws Exception {
        Files.createDirectory(fixture);Files.writeString(fixture.resolve("OWNER"),OWNER+"\n",StandardOpenOption.CREATE_NEW);
        for (int count : PIECES) {
            Path folder = Files.createDirectory(fixture.resolve("pieces-"+count)); var ids = new ArrayList<UUID>();
            try (var journal = new FileTransactionJournal(folder.resolve("journal"),new UUID(0x42524d455441L,count))) {
                var registry = ManufacturedRegistry.load(journal);
                for (int start = 0; start < count; start += 128) {
                    var plans = new ArrayList<ManufacturedPiece.Plan>();
                    for (int i = start; i < Math.min(start+128,count); i++) plans.add(new ManufacturedPiece.Plan(STEEL,cells(i,false)));
                    long revision = registry.lastCommittedRevision(DIMENSION);
                    var request = new Request(new UUID(40,start/128+1),ACTOR,SESSION,journal.domain(),revision,"ab".repeat(32));
                    var prepared = registry.prepareBuild(request,DIMENSION,false,plans);
                    // Input order is part of the fixture oracle, independent of the registry's map ordering.
                    for (int i = start; i < Math.min(start+128,count); i++) {
                        BlockKey first = cells(i,false).get(0); UUID id = null;
                        for (Change change : prepared.metadata()) if (change.resource().startsWith("piece/")) {
                            var p = MetadataCodec.piece(change.after()); if (p.cells().get(0).equals(first)) id = p.id();
                        }
                        require(id != null,"missing explicit plan"); ids.add(id);
                    }
                    journal.create(Entry.prepared(prepared.withParticipants(List.of())));
                    journal.decide(request.id(),Phase.COMMITTED,Reason.NONE); registry.applyCommitted(journal,request.id());
                }
                verify(registry,ids,false);
            }
            Files.write(folder.resolve("ids.txt"),ids.stream().map(UUID::toString).toList(),StandardOpenOption.CREATE_NEW);
            System.out.println("Seeded "+count+" explicit pieces, "+count*32+" owned cells");
        }
    }
    private static Request editRequest(ManufacturedRegistry registry) {
        return new Request(new UUID(70,1),ACTOR,SESSION,registry.domain(),registry.lastCommittedRevision(DIMENSION),"cd".repeat(32));
    }
    private static List<BlockKey> cells(int piece, boolean edited) {
        var result = new ArrayList<BlockKey>();
        for (int k = edited ? 1 : 0; k < 32; k++) result.add(new BlockKey((piece%4)*32+k,80,piece/4));
        return result;
    }
    private static String verify(ManufacturedRegistry registry, List<UUID> ids, boolean edited) throws Exception {
        require(registry.lifetimePieces() == ids.size() && registry.ownedCells() == ids.size()*32-(edited ? 1 : 0),"registry capacity/count");
        var digest = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < ids.size(); i++) {
            ManufacturedPiece p = registry.piece(ids.get(i)).orElseThrow(); boolean changed = edited && i == 0;
            require(p.cells().equals(cells(i,changed)) && p.declaration().equals(STEEL) && p.actor().equals(ACTOR)
                    && p.status() == (changed ? ManufacturedPiece.Status.EDITED : ManufacturedPiece.Status.INTACT),"piece changed");
            require(p.birthTransaction().equals(new UUID(40,i/128+1)) && p.birthOrder() == i/128+1 && p.birthRevision() == i/128+1,"birth identity changed");
            for (BlockKey cell : p.cells()) require(registry.owner(DIMENSION,cell).orElseThrow().equals(p.id()),"ownership changed");
            digest.update(MetadataCodec.piece(p).bytes());
        }
        if (edited) require(registry.owner(DIMENSION,new BlockKey(0,80,0)).isEmpty(),"released cell retained");
        long expected = (ids.size()+127)/128+(edited ? 1 : 0);
        require(registry.order() == expected && registry.lastCommittedRevision(DIMENSION) == expected,"order/revision changed");
        return HexFormat.of().formatHex(digest.digest());
    }
    private static long allocation() { return ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().getId()); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static Path absolute(String value) {
        Path path = Path.of(value); if (!path.isAbsolute()) throw new IllegalArgumentException("Absolute owned paths required");return path.normalize();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void row(BufferedWriter csv,String phase,int pieces,int iteration,long ns,long allocated,String fingerprint) throws IOException {
        require(ns >= 0 && allocated >= 0,"invalid clock/allocation sample");
        csv.write(phase+"\t"+pieces+"\t"+pieces*32+"\t"+iteration+"\t"+(iteration<WARMUPS)+"\t"+ns+"\t"+allocated+"\t"+fingerprint+"\n");csv.flush();
    }
}
