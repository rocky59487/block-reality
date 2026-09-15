package com.blockreality.impl.server.construction;

import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ChunkFileParticipantTest {
    @TempDir Path directory;
    private static final int VERSION = 3465;
    private static final ChunkPos A = new ChunkPos(0,0), B = new ChunkPos(-33,32);

    static CompoundTag chunk(ChunkPos pos) {
        CompoundTag result = new CompoundTag();
        result.putInt("xPos",pos.x); result.putInt("zPos",pos.z); result.putInt("yPos",-4);
        result.putString("Status","minecraft:full"); result.putInt("DataVersion",VERSION);
        result.putLong("LastUpdate",123456L); result.putLong("InhabitedTime",7654321L);
        ListTag sections = new ListTag();
        for (int y : new int[]{-4,5,19}) {
            CompoundTag section = new CompoundTag(); section.putByte("Y",(byte)y);
            CompoundTag blocks = new CompoundTag(); ListTag palette = new ListTag();
            CompoundTag state = new CompoundTag(); state.putString("Name","minecraft:stone"); palette.add(state);
            blocks.put("palette",palette); section.put("block_states",blocks);
            CompoundTag biomes = new CompoundTag(); ListTag names = new ListTag(); names.add(StringTag.valueOf("minecraft:plains"));
            biomes.put("palette",names); section.put("biomes",biomes); sections.add(section);
        }
        result.put("sections",sections);
        ListTag entities = new ListTag(); CompoundTag entity = new CompoundTag();
        entity.putString("id","fixture:block_entity"); entity.putInt("x",pos.getMinBlockX()); entity.putInt("y",80); entity.putInt("z",pos.getMinBlockZ());
        CompoundTag customItem = new CompoundTag(); customItem.putString("display","不可遺失"); customItem.putIntArray("ints",new int[]{-1,0,7});
        entity.put("item",customItem); entities.add(entity); result.put("block_entities",entities);
        CompoundTag caps = new CompoundTag(); caps.putLong("custom_owner",-987654321L); caps.putByteArray("payload",new byte[]{1,3,-2,7});
        result.put("ForgeCaps",caps); result.put("custom_save_hook",customItem.copy());
        CompoundTag heightmaps = new CompoundTag(); heightmaps.putLongArray("WORLD_SURFACE",new long[]{1,Long.MIN_VALUE,Long.MAX_VALUE});
        result.put("Heightmaps",heightmaps);
        ListTag ticks = new ListTag(); CompoundTag tick = new CompoundTag(); tick.putString("i","minecraft:stone"); tick.putInt("t",10); ticks.add(tick);
        result.put("block_ticks",ticks); result.put("fluid_ticks",new ListTag());
        return result;
    }
    static byte[] bytes(CompoundTag tag) throws IOException { return CanonicalNbt.encode(tag,ChunkFileParticipant.MAX_CHUNK_BYTES); }

    static class MemoryStorage implements ChunkFileParticipant.Storage {
        final Map<ChunkPos,CompoundTag> saved = new ConcurrentHashMap<>();
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        String fault = "";
        int forces;
        public CompletableFuture<Void> store(ChunkPos pos,CompoundTag image) {
            calls.add("store:"+pos); saved.put(pos,image.copy());
            return fault.equals("store") ? CompletableFuture.failedFuture(new IOException("store failed after write")) : CompletableFuture.completedFuture(null);
        }
        public CompletableFuture<Void> synchronize(boolean force) {
            calls.add("sync:"+force); if (force) forces++;
            return fault.equals("sync") ? CompletableFuture.failedFuture(new IOException("force failed")) : CompletableFuture.completedFuture(null);
        }
        public CompletableFuture<Optional<CompoundTag>> load(ChunkPos pos) {
            calls.add("load:"+pos);
            if (fault.equals("load")) return CompletableFuture.failedFuture(new IOException("read failed"));
            CompoundTag tag = saved.get(pos); tag = tag == null ? null : tag.copy();
            if (tag != null && fault.equals("wrong")) tag.remove("custom_save_hook");
            if (tag != null && fault.equals("header")) tag.putInt("xPos",123);
            return CompletableFuture.completedFuture(Optional.ofNullable(fault.equals("missing") ? null : tag));
        }
    }

    @Test void wholeImagesArePrivateAndForcingPrecedesVerifiedReadback() throws Exception {
        var storage = new MemoryStorage(); var participant = new ChunkFileParticipant(storage,VERSION);
        CompoundTag source = chunk(A); byte[] original = bytes(source);
        var batch = participant.prepare(Map.of(A,source));
        source.getCompound("ForgeCaps").putString("foreign","later caller edit");
        batch.image(A).getCompound("custom_save_hook").putString("display","mutated readout");
        assertThrows(UnsupportedOperationException.class,() -> batch.positions().clear());
        assertThrows(IllegalArgumentException.class,() -> batch.image(B));
        assertEquals(List.of("sync:true"),storage.calls);
        participant.persist(batch);
        assertEquals(List.of("sync:true","store:"+A,"sync:true","load:"+A),storage.calls);
        assertEquals(2,storage.forces);
        assertArrayEquals(original,bytes(storage.saved.get(A)));
        assertArrayEquals(original,bytes(participant.read(List.of(A)).image(A)));
        assertEquals("sync:true",storage.calls.get(4));
        // A later host-supplied after-image can replace only one field while retaining all others.
        CompoundTag after = batch.image(A); after.putLong("LastUpdate",123457L);
        participant.persist(participant.prepare(Map.of(A,after)));
        assertEquals(chunk(A).get("block_entities"),storage.saved.get(A).get("block_entities"));
        assertEquals(chunk(A).get("ForgeCaps"),storage.saved.get(A).get("ForgeCaps"));
        participant.persist(batch); assertArrayEquals(original,bytes(storage.saved.get(A)));
    }

    @Test void invalidLastImageAndReducedBatchLimitsCannotIssuePartialWrites() throws Exception {
        var storage = new MemoryStorage(); var participant = new ChunkFileParticipant(storage,VERSION,2,100000);
        var images = new LinkedHashMap<ChunkPos,CompoundTag>(); images.put(A,chunk(A));
        CompoundTag invalid = chunk(B); invalid.putInt("zPos",123); images.put(B,invalid);
        assertThrows(IOException.class,() -> participant.prepare(images));
        assertTrue(storage.saved.isEmpty()); assertEquals(List.of("sync:true"),storage.calls);
        assertThrows(IOException.class,() -> participant.prepare(Map.of()));
        assertThrows(IOException.class,() -> participant.prepare(Map.of(A,chunk(A),B,chunk(B),new ChunkPos(3,3),chunk(new ChunkPos(3,3)))));
        int one = bytes(chunk(A)).length;
        var small = new ChunkFileParticipant(storage,VERSION,2,one+1);
        assertThrows(IOException.class,() -> small.prepare(Map.of(A,chunk(A),B,chunk(B))));
        assertTrue(storage.saved.isEmpty());
        var valid = small.prepare(Map.of(A,chunk(A))); small.persist(valid);
        assertThrows(IOException.class,() -> participant.persist(valid));
        participant.persist(participant.prepare(Map.of(B,chunk(B))));
        assertEquals(2,storage.saved.size());
        assertThrows(IllegalArgumentException.class,() -> new ChunkFileParticipant(storage,VERSION,257,100));
        assertThrows(IllegalArgumentException.class,() -> new ChunkFileParticipant(storage,VERSION,1,ChunkFileParticipant.MAX_BATCH_BYTES+1));
    }

    @Test void headersCoordinatesSectionTypeAndCanonicalBudgetsRefuseBeforeStorage() throws Exception {
        var storage = new MemoryStorage(); var participant = new ChunkFileParticipant(storage,VERSION);
        List<Consumer<CompoundTag>> corruptions = List.of(
            t -> t.remove("xPos"), t -> t.putLong("xPos",0), t -> t.putInt("zPos",1),
            t -> t.putInt("DataVersion",VERSION-1), t -> t.remove("DataVersion"),
            t -> t.putString("Status","minecraft:light"), t -> t.putInt("yPos",128), t -> t.remove("sections"),
            t -> { ListTag bad = new ListTag(); bad.add(IntTag.valueOf(1)); t.put("sections",bad); },
            t -> t.putByteArray("too_large",new byte[ChunkFileParticipant.MAX_CHUNK_BYTES]),
            t -> { CompoundTag cursor=t; for(int i=0;i<65;i++) { CompoundTag next=new CompoundTag(); cursor.put("deep",next); cursor=next; } }
        );
        for (var corrupt : corruptions) {
            CompoundTag tag = chunk(A); corrupt.accept(tag);
            assertThrows(IOException.class,() -> participant.prepare(Map.of(A,tag)));
        }
        for (ChunkPos invalid : List.of(new ChunkPos(-1875001,0),new ChunkPos(1875000,0),new ChunkPos(0,-1875001),new ChunkPos(0,1875000)))
            assertThrows(IOException.class,() -> participant.prepare(Map.of(invalid,chunk(invalid))));
        assertEquals(List.of("sync:true"),storage.calls); assertTrue(storage.saved.isEmpty());
        participant.persist(participant.prepare(Map.of(A,chunk(A))));
    }

    @Test void everyStorageFailureLatchesAndNewAdapterResolvesActualWrittenImage() throws Exception {
        for (String fault : List.of("store","sync","load","wrong","header","missing")) {
            var storage = new MemoryStorage(); var participant = new ChunkFileParticipant(storage,VERSION);
            var batch = participant.prepare(Map.of(A,chunk(A))); storage.fault=fault;
            assertThrows(IOException.class,() -> participant.persist(batch),fault);
            assertThrows(IOException.class,() -> participant.prepare(Map.of(A,chunk(A))),fault);
            assertThrows(IOException.class,() -> participant.read(List.of(A)),fault);
            storage.fault="";
            var recovery = new ChunkFileParticipant(storage,VERSION);
            assertArrayEquals(bytes(chunk(A)),bytes(recovery.read(List.of(A)).image(A)),fault);
        }
        var storage = new MemoryStorage(); storage.fault="sync";
        assertThrows(IOException.class,() -> new ChunkFileParticipant(storage,VERSION));
        assertTrue(storage.saved.isEmpty());
    }

    @Test void missingReadDoesNotInventAirAndForeignThreadDoesNotPoisonOwner() throws Exception {
        var storage = new MemoryStorage(); var participant = new ChunkFileParticipant(storage,VERSION);
        var other = Executors.newSingleThreadExecutor();
        try {
            other.submit(() -> {
                assertThrows(IOException.class,() -> participant.prepare(Map.of(A,chunk(A))));
                assertThrows(IOException.class,() -> participant.read(List.of(A)));
            }).get(5,TimeUnit.SECONDS);
        } finally { other.shutdownNow(); }
        assertThrows(IOException.class,() -> participant.read(List.of(A,A)));
        participant.prepare(Map.of(A,chunk(A))); // Invalid input/thread never touched storage or readiness.
        assertThrows(IOException.class,() -> participant.read(List.of(A)));
        assertThrows(IOException.class,() -> participant.prepare(Map.of(A,chunk(A))));
        assertTrue(storage.saved.isEmpty());
    }

    @Test void outstandingStoresDrainBeforeFailureAndBeforeAnyForceOrRead() throws Exception {
        for (boolean synchronousFailure : List.of(false,true)) {
            CountDownLatch issued = new CountDownLatch(2);
            CompletableFuture<Void> first = new CompletableFuture<>(), second = new CompletableFuture<>();
            var storage = new MemoryStorage() {
                int index;
                public CompletableFuture<Void> store(ChunkPos pos,CompoundTag image) {
                    int n=++index; calls.add("store:"+pos); saved.put(pos,image.copy()); issued.countDown();
                    if (n==2 && synchronousFailure) throw new IllegalStateException("late issuance failure");
                    return n==1 ? first : second;
                }
            };
            var executor = Executors.newSingleThreadExecutor();
            try {
                var completed = executor.submit(() -> {
                    var participant = new ChunkFileParticipant(storage,VERSION);
                    var images = new LinkedHashMap<ChunkPos,CompoundTag>(); images.put(A,chunk(A)); images.put(B,chunk(B));
                    var batch = participant.prepare(images);
                    if (synchronousFailure) assertThrows(IllegalStateException.class,() -> participant.persist(batch));
                    else assertThrows(IOException.class,() -> participant.persist(batch));
                    assertThrows(IOException.class,() -> participant.read(List.of(A))); return null;
                });
                assertTrue(issued.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,() -> completed.get(100,TimeUnit.MILLISECONDS));
                assertEquals(1,storage.forces); // Only construction's opening barrier has happened.
                if (!synchronousFailure) {
                    first.completeExceptionally(new IOException("first store failed"));
                    assertThrows(TimeoutException.class,() -> completed.get(100,TimeUnit.MILLISECONDS));
                    assertEquals(1,storage.forces); second.complete(null);
                } else first.complete(null);
                completed.get(5,TimeUnit.SECONDS);
                assertEquals(2,storage.forces); assertTrue(storage.calls.stream().noneMatch(s -> s.startsWith("load:")));
            } finally { first.complete(null); second.complete(null); executor.shutdownNow(); }
        }
    }

    private static final class DiskWorker extends IOWorker {
        DiskWorker(Path directory) { super(directory,false,"br-construction-test"); }
    }

    @Test void actualRegionWorkerPersistsCompleteMultipleChunksAndReopensExactBytes() throws Exception {
        var images = new LinkedHashMap<ChunkPos,CompoundTag>(); images.put(A,chunk(A)); images.put(B,chunk(B));
        try (var worker = new DiskWorker(directory)) {
            var participant = new ChunkFileParticipant(ChunkFileParticipant.storage(worker),VERSION);
            var before = participant.prepare(images); participant.persist(before);
            CompoundTag edited = chunk(A); edited.getCompound("ForgeCaps").putInt("new_field",7);
            participant.persist(participant.prepare(Map.of(A,edited)));
            assertArrayEquals(bytes(edited),bytes(participant.read(List.of(A)).image(A)));
            participant.persist(before); // The exact same acknowledged path restores a known baseline.
        }
        try (var worker = new DiskWorker(directory)) {
            var participant = new ChunkFileParticipant(ChunkFileParticipant.storage(worker),VERSION);
            var reloaded = participant.read(List.of(A,B));
            assertArrayEquals(bytes(chunk(A)),bytes(reloaded.image(A)));
            assertArrayEquals(bytes(chunk(B)),bytes(reloaded.image(B)));
            assertThrows(IOException.class,() -> participant.read(List.of(new ChunkPos(100,100))));
        }
    }
}
