package com.blockreality.impl.server.construction;

import com.mojang.datafixers.util.Either;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundedChunkReadTest {
    @TempDir Path directory;
    private static final int VERSION = 3465, LIMIT = ChunkFileParticipant.MAX_CHUNK_BYTES;
    private static final ChunkPos POS = new ChunkPos(-1,32);
    private static final class Worker extends IOWorker {
        Worker(Path path) { super(path,false,"br-bounded-chunk-test"); }
    }

    @Test void decodedByteCapStopsTheStreamBeforeTagAllocation() throws Exception {
        var stream = new InputStream() {
            int delivered;
            public int read() { if (delivered==2*LIMIT) return -1; delivered++; return 0; }
            public int read(byte[] b,int off,int len) {
                if (delivered==2*LIMIT) return -1;
                int count=Math.min(len,2*LIMIT-delivered); Arrays.fill(b,off,off+count,(byte)0); delivered+=count; return count;
            }
        };
        assertThrows(IOException.class,() -> BoundedChunkRead.parse(stream));
        assertEquals(LIMIT+1,stream.delivered);
        for (int kind : new int[]{7,11,12}) {
            byte[] declaredHuge = CanonicalNbtTest.raw(kind,out -> out.writeInt(Integer.MAX_VALUE));
            assertThrows(IOException.class,() -> BoundedChunkRead.parse(new ByteArrayInputStream(declaredHuge)));
        }
        byte[] list = CanonicalNbtTest.raw(9,out -> { out.writeByte(10); out.writeInt(Integer.MAX_VALUE); });
        assertThrows(IOException.class,() -> BoundedChunkRead.parse(new ByteArrayInputStream(list)));
    }

    @Test void actualWorkerReopensEveryTagAndUnsortedFullChunkWithoutLoss() throws Exception {
        CompoundTag tag = ChunkFileParticipantTest.chunk(POS);
        tag.putByte("byte",(byte)-128);tag.putShort("short",Short.MIN_VALUE); tag.putInt("int",Integer.MIN_VALUE);
        tag.putLong("long",Long.MAX_VALUE); tag.putFloat("float",Float.MIN_VALUE); tag.putDouble("double",Double.NEGATIVE_INFINITY);
        tag.putByteArray("bytes",new byte[]{-1,0,127}); tag.putIntArray("ints",new int[]{Integer.MIN_VALUE,1});
        tag.putLongArray("longs",new long[]{Long.MAX_VALUE,-1}); tag.putString("text","鋼骨\u0000\ud83e\uddf1");
        var canonical = ChunkFileParticipantTest.bytes(tag); var raw = new ByteArrayOutputStream();
        NbtIo.write(tag,new DataOutputStream(raw)); // Vanilla compound order need not match canonical key order.
        Files.write(directory.resolve("expected-canonical.nbt"),canonical);
        try (var worker = new Worker(directory.resolve("region"))) {
            write(worker,raw.toByteArray());
            var participant = new ChunkFileParticipant(ChunkFileParticipant.storage(worker),VERSION);
            assertArrayEquals(canonical,ChunkFileParticipantTest.bytes(participant.read(List.of(POS)).image(POS)));
            var original=participant.prepare(Map.of(POS,tag)); CompoundTag after=tag.copy(); after.getCompound("ForgeCaps").putInt("changed",2);
            participant.persist(participant.prepare(Map.of(POS,after)));
            assertArrayEquals(ChunkFileParticipantTest.bytes(after),ChunkFileParticipantTest.bytes(participant.read(List.of(POS)).image(POS)));
            participant.persist(original);
        }
        try (var worker = new Worker(directory.resolve("region"))) {
            var participant = new ChunkFileParticipant(ChunkFileParticipant.storage(worker),VERSION);
            assertArrayEquals(canonical,ChunkFileParticipantTest.bytes(participant.read(List.of(POS)).image(POS)));
            assertTrue(BoundedChunkRead.load(worker,new ChunkPos(0,32)).get(5,TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test void duplicateNamesRefuseWithoutChangingRegionFiles() throws Exception {
        byte[] canonical = ChunkFileParticipantTest.bytes(ChunkFileParticipantTest.chunk(POS));
        var raw = new ByteArrayOutputStream();raw.write(canonical,0,canonical.length-1);
        var data = new DataOutputStream(raw);data.writeByte(3);data.writeUTF("xPos");data.writeInt(POS.x);data.writeByte(0);
        refused(raw.toByteArray(),"duplicate");
    }

    @Test void compressedOversizedDocumentRefusesBeforeNbtConstructionAndPreservesFiles() throws Exception {
        CompoundTag tag = ChunkFileParticipantTest.chunk(POS);tag.putByteArray("oversized",new byte[LIMIT+1]);
        var raw = new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(raw));
        refused(raw.toByteArray(),"oversized");
        try(var files=Files.list(directory.resolve("oversized/region"))) {
            // A small compressed region can contain more decoded data than the admitted byte budget.
            assertTrue(files.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch(IOException e) { throw new UncheckedIOException(e); } }).sum()<LIMIT);
        }
    }

    @Test void malformedAndDeepRegionDocumentsRefuseWithOriginalBytesRetained() throws Exception {
        byte[] valid=ChunkFileParticipantTest.bytes(ChunkFileParticipantTest.chunk(POS));
        refused(Arrays.copyOf(valid,valid.length-1),"truncated");
        refused(Arrays.copyOf(valid,valid.length+1),"trailing");
        refused(CanonicalNbtTest.raw(7,out -> out.writeInt(Integer.MAX_VALUE)),"impossible-array");
        refused(CanonicalNbtTest.raw(13,out -> { }),"unknown-type");
        var named=new ByteArrayOutputStream();var out=new DataOutputStream(named);out.writeByte(10);out.writeUTF("foreign-root");out.writeByte(0);
        refused(named.toByteArray(),"named-root");
        CompoundTag deep=ChunkFileParticipantTest.chunk(POS),cursor=deep;
        for(int i=0;i<65;i++) { CompoundTag next=new CompoundTag();cursor.put("deep",next);cursor=next; }
        var raw=new ByteArrayOutputStream();NbtIo.write(deep,new DataOutputStream(raw));refused(raw.toByteArray(),"deep");
        CompoundTag foreign=ChunkFileParticipantTest.chunk(POS);foreign.putInt("xPos",0);
        refused(ChunkFileParticipantTest.bytes(foreign),"foreign-position");
    }

    @Test void pendingMapNeverSubstitutesForForcedRegionData() throws Exception {
        try(var worker=new Worker(directory.resolve("region"))) {
            CountDownLatch active=new CountDownLatch(1),release=new CountDownLatch(1);
            var held=worker.<Void>submitTask(() -> {
                active.countDown();
                try {
                    if(!release.await(5,TimeUnit.SECONDS)) return Either.right(new IOException("test queue was not released"));
                    return Either.left(null);
                } catch(InterruptedException e) { Thread.currentThread().interrupt();return Either.right(e); }
            });
            try {
                assertTrue(active.await(5,TimeUnit.SECONDS));
                var store=worker.store(POS,ChunkFileParticipantTest.chunk(POS));
                var read=BoundedChunkRead.load(worker,POS);release.countDown();
                var failure=assertThrows(ExecutionException.class,() -> read.get(5,TimeUnit.SECONDS));
                assertInstanceOf(IOException.class,failure.getCause());assertTrue(failure.getCause().getMessage().contains("pending"));
                store.get(5,TimeUnit.SECONDS);worker.synchronize(true).get(5,TimeUnit.SECONDS);
                assertArrayEquals(ChunkFileParticipantTest.bytes(ChunkFileParticipantTest.chunk(POS)),
                    ChunkFileParticipantTest.bytes(BoundedChunkRead.load(worker,POS).get(5,TimeUnit.SECONDS).orElseThrow()));
            } finally { release.countDown();held.get(5,TimeUnit.SECONDS); }
        }
    }

    private void refused(byte[] raw,String name) throws Exception {
        Path caseRoot=Files.createDirectory(directory.resolve(name)),region=caseRoot.resolve("region");
        Files.writeString(caseRoot.resolve("raw-size-and-sha256.txt"),raw.length+"\n"+hash(raw)+"\n");
        try(var worker=new Worker(region)) {
            write(worker,raw);Map<String,String> before=hashes(region);
            for(int i=0;i<2;i++) {
                var participant=new ChunkFileParticipant(ChunkFileParticipant.storage(worker),VERSION);
                assertThrows(IOException.class,() -> participant.read(List.of(POS)),name);
                assertThrows(IOException.class,() -> participant.prepare(Map.of(POS,ChunkFileParticipantTest.chunk(POS))),name);
                assertEquals(before,hashes(region),name);
            }
        }
        Map<String,String> before=hashes(region);
        try(var worker=new Worker(region)) {
            var participant=new ChunkFileParticipant(ChunkFileParticipant.storage(worker),VERSION);
            assertThrows(IOException.class,() -> participant.read(List.of(POS)),name);
        }
        assertEquals(before,hashes(region),name);
        Files.writeString(caseRoot.resolve("region-file-hashes.txt"),before.toString()+"\n");
    }
    private static void write(IOWorker worker,byte[] raw) throws Exception {
        worker.<Void>submitTask(() -> {
            try(var out=worker.storage.getRegionFile(POS).getChunkDataOutputStream(POS)) { out.write(raw);return Either.left(null); }
            catch(Exception e) { return Either.right(e); }
        }).get(5,TimeUnit.SECONDS);
        worker.synchronize(true).get(5,TimeUnit.SECONDS);
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static Map<String,String> hashes(Path folder) throws Exception {
        var result=new TreeMap<String,String>();
        try(var files=Files.walk(folder)) { for(Path p:files.filter(Files::isRegularFile).toList()) result.put(folder.relativize(p).toString(),hash(Files.readAllBytes(p))); }
        return result;
    }
    /** Opt-in retained fixtures; normal unit tests keep their usual temporary-directory lifecycle. */
    @AfterEach void retainFixtures(TestInfo info) throws Exception {
        String requested=System.getProperty("br.chunkReadEvidence");if(requested==null) return;
        Path root=Path.of(requested);if(!root.isAbsolute()) throw new IOException("Absolute fixture receipt directory required");
        String name=info.getTestMethod().orElseThrow().getName();Path destination=root.resolve(name);
        Files.createDirectories(root);Files.createDirectory(destination);
        try(var files=Files.walk(directory)) {
            for(Path p:files.toList()) {
                Path target=destination.resolve(directory.relativize(p));
                if(Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p,target);
            }
        }
    }
}
