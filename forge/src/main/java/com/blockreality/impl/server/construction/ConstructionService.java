package com.blockreality.impl.server.construction;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.transaction.*;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.block.StructuralBlock;
import com.blockreality.impl.server.StructureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

/** Server owner of module construction. The first gameplay entry edits an existing product's axis. */
public final class ConstructionService {
    private static final Map<MinecraftServer,ConstructionService> SERVERS = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final UUID session = UUID.randomUUID();
    private FileTransactionJournal journal;
    private ManufacturedRegistry metadata;
    private AtomicConstructionCoordinator coordinator;
    private volatile boolean busy = true, ready, publishing;
    private volatile String failure = "", exclusionFailure = "";
    private boolean bootstrapping = true;
    private ServerLevel ownedLevel;
    private Set<BlockPos> ownedCells = Set.of();
    private final Set<UUID> deferredSnapshots = new LinkedHashSet<>();

    private ConstructionService(MinecraftServer server) {
        this.server = server;
        try {
            journal = FileTransactionJournal.open(server.getWorldPath(LevelResource.ROOT).resolve("blockreality/construction"));
            metadata = ManufacturedRegistry.load(journal);
            coordinator = new AtomicConstructionCoordinator(journal);
        } catch (Exception problem) { fail(problem); busy = false; }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void aboutToStart(ServerAboutToStartEvent event) {
        ConstructionService service = new ConstructionService(event.getServer());
        if (SERVERS.putIfAbsent(event.getServer(),service) != null) {
            service.close(); throw new IllegalStateException("Construction server already owns a journal");
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void starting(ServerStartingEvent event) {
        ConstructionService service = SERVERS.get(event.getServer());
        if (service != null) service.recoverStartup();
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        ConstructionService service = SERVERS.remove(event.getServer());
        if (service != null) service.close();
    }
    private void close() {
        ready = false;
        if (journal != null) try { journal.close(); } catch (IOException problem) { BlockRealityMod.LOG.error("Construction journal close failed",problem); }
    }

    private void recoverStartup() {
        if (!failure.isEmpty()) return;
        try {
            checkThread(); busy = true;
            Optional<UUID> pending = journal.pending();
            Entry entry = pending.isPresent() ? journal.verify(pending.get()).orElseThrow(() -> new IOException("Prepared entry disappeared")) : null;
            Host host = new Host(entry,true);
            coordinator.recover(host); ready = true;
        } catch (Exception problem) { fail(problem); }
        finally { bootstrapping = false; busy = false; ownedLevel = null; ownedCells = Set.of(); drainSnapshots(); }
    }

    public static boolean busy(MinecraftServer server) {
        ConstructionService service = SERVERS.get(server); return service == null || service.busy;
    }
    public static boolean available(MinecraftServer server) {
        ConstructionService service = SERVERS.get(server);
        return service != null && service.ready && !service.busy && service.failure.isEmpty();
    }
    public static String failureMessage(MinecraftServer server) {
        ConstructionService service = SERVERS.get(server); return service == null ? "" : service.failure;
    }
    public static boolean publishing(MinecraftServer server) {
        ConstructionService service = SERVERS.get(server); return service != null && service.publishing;
    }
    public static void rejectReentrant(MinecraftServer server) {
        if (busy(server)) throw new IllegalStateException("Construction is publishing a private transaction");
    }
    public static boolean suppressObservation(ServerLevel level) { return !available(level.getServer()); }
    public static boolean suppressStructuralChange(ServerLevel level, BlockPos pos) {
        ConstructionService service = SERVERS.get(level.getServer());
        if (service == null) return true;
        if (!service.busy) return !service.ready || !service.failure.isEmpty();
        if (!service.bootstrapping && (level != service.ownedLevel || !service.ownedCells.contains(pos)))
            service.exclusionFailure = "Structural change outside construction participants";
        return true;
    }

    /** Login callbacks cannot send a private revision, even if a foreign hook invokes them reentrantly. */
    public static boolean deferSnapshot(ServerPlayer player) {
        ConstructionService service = SERVERS.get(player.getServer());
        if (service == null) return true;
        if (!service.busy) return false;
        if (service.deferredSnapshots.size() >= 1024 && !service.deferredSnapshots.contains(player.getUUID()))
            service.exclusionFailure = "Construction deferred snapshot capacity exceeded";
        else service.deferredSnapshots.add(player.getUUID());
        return true;
    }
    private void drainSnapshots() {
        for (UUID id : List.copyOf(deferredSnapshots)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) StructureManager.sendSnapshot(player);
        }
        deferredSnapshots.clear();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void guardPlacement(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && !available(level.getServer())
                && event.getPlacedBlock().getBlock() instanceof StructuralBlock) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void guardBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && !available(level.getServer())
                && event.getState().getBlock() instanceof StructuralBlock) event.setCanceled(true);
    }

    public static boolean cycleAxis(ServerPlayer player, BlockPos position, BlockState expected, InteractionHand hand) {
        ConstructionService service = SERVERS.get(player.getServer());
        return service != null && service.editAxis(player,position.immutable(),expected,hand);
    }
    private boolean editAxis(ServerPlayer player, BlockPos pos, BlockState expected, InteractionHand hand) {
        checkThread();
        if (!available(server)) return false;
        ServerLevel level = player.serverLevel();
        if (!validAxisInteraction(player,level,pos,expected,hand)) return false;
        busy = true; ownedLevel = level; ownedCells = Set.of(pos); exclusionFailure = "";
        try {
            BlockState after = expected.setValue(StructuralBlock.AXIS,expected.getValue(StructuralBlock.AXIS).next());
            String dimension = level.dimension().location().toString();
            long base = StructureManager.of(level).gate().current().value();
            Request request = new Request(UUID.randomUUID(),player.getUUID(),session,journal.domain(),base,axisHash(dimension,pos,expected,after));
            var change = new Change(CellStateImage.key(pos),CellStateImage.encode(expected),CellStateImage.encode(after));
            var prepared = metadata.prepareEdit(request,dimension,Set.of(new BlockKey(pos.getX(),pos.getY(),pos.getZ())),Set.of());
            Intent intent = prepared.withParticipants(List.of(change)); Host host = new Host(Entry.prepared(intent),false);
            Receipt receipt = coordinator.execute(request,ignored -> {
                if (!validAxisInteraction(player,level,pos,expected,hand)) throw new AtomicConstructionCoordinator.ValidationRefused();
                return intent;
            },host);
            return receipt.phase() == Phase.COMMITTED;
        } catch (Exception problem) {
            if (!coordinator.ready() || !exclusionFailure.isEmpty()) fail(problem);
            else BlockRealityMod.LOG.warn("Construction axis edit refused without an unresolved decision",problem);
            return false;
        } finally {
            if (!exclusionFailure.isEmpty() || !coordinator.ready()) fail(new IOException("Construction exclusion or recovery required"));
            publishing = false; busy = false; ownedLevel = null; ownedCells = Set.of(); drainSnapshots();
        }
    }
    private static boolean validAxisInteraction(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState expected, InteractionHand hand) {
        return expected.getBlock() instanceof StructuralBlock && player.isAlive() && !player.isSpectator()
                && player.isShiftKeyDown() && player.mayBuild() && player.getItemInHand(hand).isEmpty()
                && !level.captureBlockSnapshots && !level.restoringBlockSnapshots
                && !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.getChunkSource().getChunkNow(pos.getX() >> 4,pos.getZ() >> 4) != null
                && player.canReachRaw(pos,1.5) && level.mayInteract(player,pos) && currentState(level,pos) == expected;
    }
    private static BlockState currentState(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4,pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }
    private static String axisHash(String dimension, BlockPos pos, BlockState before, BlockState after) throws Exception {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeUTF("axis-v1"); out.writeUTF(dimension); out.writeUTF(CellStateImage.key(pos));
        for (BlockState state : List.of(before,after)) { byte[] encoded = CellStateImage.encode(state).bytes(); out.writeInt(encoded.length); out.write(encoded); }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
    private void checkThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Construction requires the server thread");
    }
    private void fail(Throwable problem) {
        ready = false; failure = "Construction recovery required; analysis paused";
        BlockRealityMod.LOG.error(failure,problem);
    }

    private final class Host implements AtomicConstructionCoordinator.Host {
        private final Entry prepared;
        private final ManufacturedRegistry.PendingMetadata context;
        private final Map<String,Change> changes = new LinkedHashMap<>();
        private final Map<String,Value> working = new HashMap<>();
        private final Map<BlockPos,BlockState> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        private final List<ChunkPos> chunks;
        private final ServerLevel level;
        private ChunkFileParticipant storage;
        private ChunkFileParticipant.Batch lastPersisted, attempted;
        private boolean storageUncertain;

        Host(Entry prepared, boolean recovering) throws IOException {
            this.prepared = prepared;
            if (prepared == null) { context = null; chunks = List.of(); level = null; storage = null; return; }
            context = metadata.validatePrepared(prepared);
            ResourceLocation location = ResourceLocation.tryParse(context.dimension());
            if (location == null || !location.toString().equals(context.dimension())) throw new IOException("Invalid construction dimension");
            level = server.getLevel(ResourceKey.create(Registries.DIMENSION,location));
            if (level == null) throw new IOException("Construction dimension unavailable");
            for (Change change : context.changes()) working.put(change.resource(),change.before());
            for (Change change : prepared.intent().changes()) {
                changes.put(change.resource(),change);
                if (working.containsKey(change.resource())) continue;
                BlockPos pos = CellStateImage.position(change.resource());
                if (level.isOutsideBuildHeight(pos)) throw new IOException("Invalid construction cell in level");
                before.put(pos,CellStateImage.decode(change.before())); after.put(pos,CellStateImage.decode(change.after()));
                if (before.size() > MAX_CELLS) throw new IOException("Construction cell capacity exceeded");
            }
            if (before.isEmpty()) throw new IOException("Construction cell participants missing");
            // Only the axis operation currently has a production validation entry. Future operations
            // must add their own plan validation before this common participant host admits them.
            if (!context.operation().equals("EDIT") || before.size() != 1) throw new IOException("Unsupported construction operation");
            var cell = before.entrySet().iterator().next(); BlockState prior = cell.getValue(), next = after.get(cell.getKey());
            if (!(prior.getBlock() instanceof StructuralBlock)
                    || prior.setValue(StructuralBlock.AXIS,prior.getValue(StructuralBlock.AXIS).next()) != next)
                throw new IOException("Invalid axis transition in construction history");
            try {
                if (!prepared.request().planHash().equals(axisHash(context.dimension(),cell.getKey(),prior,next)))
                    throw new IOException("Construction plan hash mismatch");
            } catch (IOException problem) { throw problem; }
            catch (Exception problem) { throw new IOException("Construction plan cannot be verified",problem); }
            chunks = before.keySet().stream().map(ChunkPos::new).distinct().toList();
            if (chunks.size() > ChunkFileParticipant.MAX_CHUNKS) throw new IOException("Construction chunk capacity exceeded");
            storage = ChunkFileParticipant.bind(level);
            if (recovering) {
                storage.read(chunks); // Missing/corrupt baselines cannot authorize generation or overwrite.
                for (ChunkPos chunk : chunks) level.getChunk(chunk.x,chunk.z);
            }
            for (ChunkPos chunk : chunks) if (level.getChunkSource().getChunkNow(chunk.x,chunk.z) == null)
                throw new IOException("Construction chunk is not loaded");
        }
        @Override public void checkAccess() {
            checkThread();
            if (!busy || !failure.isEmpty() || !exclusionFailure.isEmpty()) throw new IllegalStateException("Construction owner is not available");
        }
        @Override public long revision() {
            if (context == null) return 0;
            return ByteBuffer.wrap(working.get(context.revisionResource()).bytes()).getLong();
        }
        @Override public Value read(String key) throws IOException {
            checkAccess();
            if (!changes.containsKey(key)) throw new IOException("Unknown construction resource");
            if (working.containsKey(key)) return working.get(key);
            return CellStateImage.encode(readCell(CellStateImage.position(key)));
        }
        private BlockState readCell(BlockPos pos) throws IOException {
            BlockState state = currentState(level,pos);
            if (state == null) throw new IOException("Construction chunk became unavailable");
            return state;
        }
        private void requireResources(List<String> resources) throws IOException {
            checkAccess();
            if (resources.size() != changes.size() || !new HashSet<>(resources).equals(changes.keySet()))
                throw new IOException("Incomplete construction participant barrier");
        }
        @Override public void checkpoint(List<String> resources) throws IOException {
            requireResources(resources); persistCurrent(); checkAccess();
        }
        @Override public void write(String key, Value value) throws IOException {
            checkAccess(); Change change = changes.get(key);
            if (change == null || !value.equals(change.before()) && !value.equals(change.after())) throw new IOException("Foreign construction write");
            if (working.containsKey(key)) { working.put(key,value); return; }
            BlockPos pos = CellStateImage.position(key); BlockState desired = CellStateImage.decode(value);
            if (readCell(pos) != desired && !level.setBlock(pos,desired,16 | 32))
                throw new IOException("Construction cell write refused");
            if (readCell(pos) != desired) throw new IOException("Construction cell write mismatch");
        }
        @Override public void flush(List<String> resources) throws IOException {
            requireResources(resources); persistCurrent(); checkAccess();
        }
        private void persistCurrent() throws IOException {
            if (storageUncertain) {
                ChunkFileParticipant recovered = ChunkFileParticipant.bind(level);
                var actual = recovered.read(chunks);
                if (lastPersisted == null || attempted == null) throw new IOException("Chunk baseline is not recoverable in memory");
                for (ChunkPos pos : chunks) {
                    byte[] bytes = CanonicalNbt.encode(actual.image(pos),ChunkFileParticipant.MAX_CHUNK_BYTES);
                    if (!Arrays.equals(bytes,CanonicalNbt.encode(lastPersisted.image(pos),ChunkFileParticipant.MAX_CHUNK_BYTES))
                            && !Arrays.equals(bytes,CanonicalNbt.encode(attempted.image(pos),ChunkFileParticipant.MAX_CHUNK_BYTES)))
                        throw new IOException("Foreign chunk image after uncertain storage operation");
                }
                storage = recovered; storageUncertain = false;
            }
            attempted = storage.capture(level,chunks);
            try { storage.persist(attempted); }
            catch (IOException | RuntimeException | Error problem) { storageUncertain = true; throw problem; }
            lastPersisted = attempted; attempted = null;
        }
        @Override public void publish(Receipt receipt) throws Exception {
            checkAccess();
            if (receipt.phase() != Phase.COMMITTED || !receipt.request().equals(prepared.request())
                    || receipt.revision() != prepared.intent().resultRevision()
                    || StructureManager.of(level).gate().current().value() != prepared.request().baseRevision())
                throw new IOException("Construction publication binding mismatch");
            for (var cell : after.entrySet()) if (readCell(cell.getKey()) != cell.getValue())
                throw new IOException("Construction publication world mismatch");
            metadata.applyCommitted(journal,receipt.request().id());
            publishing = true;
            try {
                StructureManager.of(level).publishConstructionState(level,after,receipt.request().baseRevision());
                for (var cell : after.entrySet()) level.sendBlockUpdated(cell.getKey(),before.get(cell.getKey()),cell.getValue(),2);
                StructureManager.of(level).announceConstruction(level,receipt.revision());
            } finally { publishing = false; }
        }
        @Override public void publishRecoveredState() {
            checkAccess();
            for (ServerLevel dimension : server.getAllLevels()) {
                String id = dimension.dimension().location().toString();
                StructureManager.of(dimension).restoreConstructionBaseline(dimension,metadata.lastCommittedRevision(id),metadata.ownedCells(id));
            }
        }
    }
}
