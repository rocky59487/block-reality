package com.blockreality.impl.server.construction;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.transaction.*;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.block.StructuralBlock;
import com.blockreality.impl.server.StructureManager;
import com.blockreality.impl.net.ConstructionProtocol;
import com.blockreality.impl.net.ConstructionProtocol.*;
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
    private record Offered(PlacementCapture capture,long revision) { }
    private record RateWindow(int started,int count) { }
    private final TransientOffers<ServerPlayer,Offered> offers = new TransientOffers<>();
    private final IdentityHashMap<ServerPlayer,RateWindow> requestRates = new IdentityHashMap<>();
    private net.minecraftforge.common.util.BlockSnapshot validatingPlacement;
    private ServerPlayer validatingActor;
    private Runnable committedPlacementEffects;
    private NeighborPublication neighborPublication;
    private static final class NeighborPublication {
        final ServerLevel level;final BlockPos pos;final BlockState state;
        BlockEvent.NeighborNotifyEvent event;
        NeighborPublication(ServerLevel level,BlockPos pos,BlockState state) {
            this.level=level;this.pos=pos;this.state=state;
        }
    }

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
        offers.clear(); requestRates.clear();
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
                && event.getPlacedBlock().getBlock() instanceof StructuralBlock) {
            ConstructionService service=SERVERS.get(level.getServer());
            boolean ownedValidator=service!=null && service.busy && event.getBlockSnapshot()==service.validatingPlacement
                    && event.getEntity()==service.validatingActor && level==service.ownedLevel && service.ownedCells.contains(event.getPos());
            if(!ownedValidator)event.setCanceled(true);
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void guardBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && !available(level.getServer())
                && event.getState().getBlock() instanceof StructuralBlock) event.setCanceled(true);
    }

    /** Tag only the first synchronous root notification from our already published block change. */
    @SubscribeEvent(priority=EventPriority.HIGHEST,receiveCanceled=true)
    public static void identifyCommittedNotification(BlockEvent.NeighborNotifyEvent event) {
        if(!(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread())return;
        ConstructionService service=SERVERS.get(level.getServer());
        NeighborPublication publication=service==null?null:service.neighborPublication;
        if(publication!=null && publication.event==null && publication.level==level && publication.pos.equals(event.getPos())
                && publication.state==event.getState() && event.getNotifiedSides().equals(EnumSet.allOf(net.minecraft.core.Direction.class)))
            publication.event=event;
    }
    public static boolean alreadyAnnouncedNeighbor(BlockEvent.NeighborNotifyEvent event) {
        if(!(event.getLevel() instanceof ServerLevel level))return false;
        ConstructionService service=SERVERS.get(level.getServer());
        NeighborPublication publication=service==null?null:service.neighborPublication;
        return publication!=null && publication.event==event && publication.state==currentState(level,publication.pos);
    }

    public static boolean cycleAxis(ServerPlayer player, BlockPos position, BlockState expected, InteractionHand hand) {
        ConstructionService service = SERVERS.get(player.getServer());
        return service != null && service.editAxis(player,position.immutable(),expected,hand);
    }

    public static OfferReply preview(ServerPlayer actor,Preview request) {
        ConstructionService service=SERVERS.get(actor.getServer());
        return service==null?new OfferReply(request.query(),Status.RECOVERY,null):service.makePreview(actor,request);
    }
    private boolean admit(ServerPlayer actor) {
        checkThread();int now=server.getTickCount();RateWindow previous=requestRates.get(actor);
        if(previous==null && requestRates.size()>=4096)return false;
        if(previous==null || Integer.toUnsignedLong(now-previous.started())>=20) {requestRates.put(actor,new RateWindow(now,1));return true;}
        if(previous.count()>=8)return false;
        requestRates.put(actor,new RateWindow(previous.started(),previous.count()+1));return true;
    }
    private OfferReply makePreview(ServerPlayer actor,Preview request) {
        if(!admit(actor))return new OfferReply(request.query(),Status.RATE_LIMITED,null);
        offers.remove(actor);
        if(!available(server))return new OfferReply(request.query(),failure.isEmpty()?Status.BUSY:Status.RECOVERY,null);
        try {
            long revision=StructureManager.of(actor.serverLevel()).gate().current().value();
            if(revision==Long.MAX_VALUE)return new OfferReply(request.query(),Status.CAPACITY,null);
            PlacementCapture captured=PlacementCapture.capture(actor,request,UUID.randomUUID());
            if(StructureManager.of(actor.serverLevel()).gate().current().value()!=revision)
                return new OfferReply(request.query(),Status.STALE,null);
            PlacementPlan plan=captured.plan();
            if(metadata.owner(plan.dimension(),new BlockKey(plan.target().getX(),plan.target().getY(),plan.target().getZ())).isPresent())
                return new OfferReply(request.query(),Status.CONFLICT,null);
            if(!offers.put(actor,plan.offer(),server.getTickCount(),captured.retainedBytes(),new Offered(captured,revision)))
                return new OfferReply(request.query(),Status.CAPACITY,null);
            Offer offer=new Offer(plan.offer(),journal.domain(),session,revision,plan.hash(),plan.dimension(),plan.target(),plan.product(),plan.axis(),plan.creative());
            return new OfferReply(request.query(),Status.READY,offer);
        } catch(PlacementCapture.Refused refusal) {return new OfferReply(request.query(),refusal.status,null);}
        catch(Exception failure) {
            BlockRealityMod.LOG.warn("Placement preview could not capture complete data",failure);
            return new OfferReply(request.query(),Status.INVALID,null);
        }
    }
    public static Outcome confirm(ServerPlayer actor,Confirmation confirmation) {
        ConstructionService service=SERVERS.get(actor.getServer());
        return service==null?refused(confirmation,Status.RECOVERY):service.confirmPlacement(actor,confirmation);
    }
    private Outcome confirmPlacement(ServerPlayer actor,Confirmation confirmation) {
        if(!admit(actor))return refused(confirmation,Status.RATE_LIMITED);
        if(journal==null || !journal.domain().equals(confirmation.domain()))return refused(confirmation,Status.CONFLICT);
        Request request=new Request(confirmation.id(),actor.getUUID(),confirmation.session(),confirmation.domain(),confirmation.baseRevision(),confirmation.bindingHash());
        try {
            Optional<Entry> previous=journal.verify(request.id());
            if(previous.isPresent()) {
                if(!previous.get().request().equals(request))return refused(confirmation,Status.CONFLICT);
                if(previous.get().phase()==Phase.PREPARED)return refused(confirmation,Status.RECOVERY);
                return outcome(confirmation,previous.get().receipt());
            }
        } catch(Exception problem) {fail(problem);return refused(confirmation,Status.RECOVERY);}
        if(!available(server))return refused(confirmation,failure.isEmpty()?Status.BUSY:Status.RECOVERY);
        if(!confirmation.session().equals(session))return refused(confirmation,Status.EXPIRED);
        Offered offered=offers.get(actor,confirmation.offer(),server.getTickCount()).orElse(null);
        if(offered==null)return refused(confirmation,Status.EXPIRED);
        PlacementCapture capture=offered.capture();PlacementPlan plan=capture.plan();
        try {
            if(offered.revision()!=confirmation.baseRevision() || !plan.hash().equals(confirmation.planHash())
                    || !plan.dimension().equals(actor.serverLevel().dimension().location().toString()))return refused(confirmation,Status.CONFLICT);
        } catch(IOException invalid) {return refused(confirmation,Status.INVALID);}
        busy=true;ownedLevel=actor.serverLevel();ownedCells=Set.of(plan.target());exclusionFailure="";
        try {
            AdmissionHost admission=new AdmissionHost(actor.serverLevel());
            Receipt receipt=coordinator.execute(request,ignored -> {
                try {
                    if(!capture.stillMatches(actor))throw new AtomicConstructionCoordinator.ValidationRefused();
                } catch(PlacementCapture.Refused refusal) {throw new AtomicConstructionCoordinator.ValidationRefused();}
                var protection=net.minecraftforge.common.ForgeHooks.onRightClickBlock(actor,plan.hand(),plan.clicked(),
                        new net.minecraft.world.phys.BlockHitResult(plan.hit(),plan.face(),plan.clicked(),false));
                if(protection.isCanceled() || protection.getUseItem()==net.minecraftforge.eventbus.api.Event.Result.DENY)
                    throw new AtomicConstructionCoordinator.ValidationRefused();
                if(!capture.stillMatches(actor))throw new AtomicConstructionCoordinator.ValidationRefused();
                BlockState placed=CellStateImage.decode(capture.cellAfter());StructuralBlock product=(StructuralBlock)placed.getBlock();
                var declaration=new com.blockreality.core.world.ConstructionDeclaration(product.materialToken(),product.sectionToken(),plan.axis());
                var piece=new ManufacturedPiece.Plan(declaration,List.of(new BlockKey(plan.target().getX(),plan.target().getY(),plan.target().getZ())));
                var prepared=metadata.prepareBuild(request,plan.dimension(),plan.creative(),List.of(piece));
                List<Change> participants=new ArrayList<>();participants.add(new Change(CellStateImage.key(plan.target()),capture.cellBefore(),capture.cellAfter()));
                participants.add(new Change("plan/"+request.id(),Value.missing(),plan.image()));
                if(!plan.creative())participants.add(new Change(playerKey(request.actor(),plan.slot()),capture.itemBefore(),capture.itemAfter()));
                Intent intent=prepared.withParticipants(participants);admission.delegate=new Host(Entry.prepared(intent),false,actor);
                return intent;
            },admission);
            offers.remove(actor);return outcome(confirmation,receipt);
        } catch(Exception problem) {
            if(!coordinator.ready() || !exclusionFailure.isEmpty())fail(problem);
            else BlockRealityMod.LOG.warn("Placement preparation failed without a completed decision",problem);
            try {
                Optional<Entry> actual=journal.verify(request.id());
                if(actual.isPresent() && actual.get().request().equals(request) && actual.get().phase()!=Phase.PREPARED) {
                    offers.remove(actor);return outcome(confirmation,actual.get().receipt());
                }
            } catch(Exception unresolved) {fail(unresolved);}
            return refused(confirmation,failure.isEmpty()?Status.INVALID:Status.RECOVERY);
        } finally {
            if(!exclusionFailure.isEmpty() || !coordinator.ready())fail(new IOException("Placement requires recovery"));
            publishing=false;busy=false;ownedLevel=null;ownedCells=Set.of();validatingPlacement=null;validatingActor=null;
            Runnable effects=committedPlacementEffects;committedPlacementEffects=null;
            if(effects!=null && failure.isEmpty())try {effects.run();}
            catch(RuntimeException effectFailure) {BlockRealityMod.LOG.error("Committed placement notification failed",effectFailure);}
            drainSnapshots();
        }
    }
    private static String playerKey(UUID actor,int slot) {return "player/"+actor+"/slot/"+slot;}
    private static Outcome refused(Confirmation request,Status status) {return new Outcome(request,status,request.baseRevision(),List.of());}
    private static Outcome outcome(Confirmation request,Receipt receipt) {
        Status status=switch(receipt.phase()) {case COMMITTED->Status.COMMITTED;case ABORTED->Status.ABORTED;
            case REJECTED->receipt.reason()==Reason.STALE_REVISION?Status.STALE:Status.REJECTED;default->throw new IllegalArgumentException("No terminal receipt");};
        return new Outcome(request,status,receipt.revision(),receipt.created());
    }
    private final class AdmissionHost implements AtomicConstructionCoordinator.Host {
        private final ServerLevel level;private Host delegate;
        AdmissionHost(ServerLevel level){this.level=level;}
        @Override public void checkAccess(){checkThread();if(!busy || !failure.isEmpty() || !exclusionFailure.isEmpty())throw new IllegalStateException("Placement owner unavailable");}
        @Override public long revision(){return delegate==null?StructureManager.of(level).gate().current().value():delegate.revision();}
        @Override public Value read(String key)throws Exception{return delegate.read(key);}
        @Override public void checkpoint(List<String> keys)throws Exception{delegate.checkpoint(keys);}
        @Override public void write(String key,Value value)throws Exception{delegate.write(key,value);}
        @Override public void flush(List<String> keys)throws Exception{delegate.flush(keys);}
        @Override public void publish(Receipt receipt)throws Exception{delegate.publish(receipt);}
        @Override public void publishRecoveredState(){throw new IllegalStateException("Admission is not recovery");}
    }
    @SubscribeEvent public static void logout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {invalidateOffer(event.getEntity());}
    @SubscribeEvent public static void travel(net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {invalidateOffer(event.getEntity());}
    @SubscribeEvent public static void clonePlayer(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {invalidateOffer(event.getOriginal());invalidateOffer(event.getEntity());}
    private static void invalidateOffer(net.minecraft.world.entity.player.Player player) {
        if(player instanceof ServerPlayer actor) {
            ConstructionService service=SERVERS.get(actor.getServer());
            if(service!=null){service.offers.remove(actor);service.requestRates.remove(actor);}
        }
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
        private final ServerPlayer actor;
        private final boolean recovering;
        private PlacementPlan placement;
        private PlayerInventoryParticipant inventory;
        private String inventoryKey;
        private Value materialBefore;

        Host(Entry prepared, boolean recovering) throws IOException {
            this(prepared,recovering,null);
        }
        Host(Entry prepared, boolean recovering, ServerPlayer actor) throws IOException {
            this.prepared = prepared;
            this.recovering = recovering; this.actor = actor;
            if (prepared == null) { context = null; chunks = List.of(); level = null; storage = null; return; }
            context = metadata.validatePrepared(prepared);
            ResourceLocation location = ResourceLocation.tryParse(context.dimension());
            if (location == null || !location.toString().equals(context.dimension())) throw new IOException("Invalid construction dimension");
            level = server.getLevel(ResourceKey.create(Registries.DIMENSION,location));
            if (level == null) throw new IOException("Construction dimension unavailable");
            for (Change change : context.changes()) working.put(change.resource(),change.before());
            for (Change change : prepared.intent().changes()) changes.put(change.resource(),change);
            if (context.operation().equals("BUILD")) {
                Change schema = changes.get("plan/"+prepared.request().id());
                if (schema == null || schema.before().present()) throw new IOException("Missing BUILD schema");
                placement = PlacementPlan.decode(schema.after());
                working.put(schema.resource(),schema.before());
                inventoryKey = placement.creative() ? null : playerKey(prepared.request().actor(),placement.slot());
            }
            for (Change change : prepared.intent().changes()) {
                if (working.containsKey(change.resource()) || change.resource().equals(inventoryKey)) continue;
                BlockPos pos = CellStateImage.position(change.resource());
                if (level.isOutsideBuildHeight(pos)) throw new IOException("Invalid construction cell in level");
                before.put(pos,CellStateImage.decode(change.before())); after.put(pos,CellStateImage.decode(change.after()));
                if (before.size() > MAX_CELLS) throw new IOException("Construction cell capacity exceeded");
            }
            if (before.isEmpty()) throw new IOException("Construction cell participants missing");
            if (placement != null) validateBuild();
            else validateAxis();
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
        private void validateAxis() throws IOException {
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
        }
        private void validateBuild() throws IOException {
            Request request = prepared.request(); BlockPos target = placement.target();
            if (!placement.actor().equals(request.actor()) || !placement.dimension().equals(context.dimension())
                    || placement.creative() != context.creative() || before.size()!=1 || !before.containsKey(target)
                    || !(target.equals(placement.clicked()) || target.equals(placement.clicked().relative(placement.face())))
                    || !new Confirmation(request.id(),placement.offer(),request.domain(),request.session(),request.baseRevision(),placement.hash())
                        .bindingHash().equals(request.planHash())) throw new IOException("BUILD request binding mismatch");
            BlockState state = after.get(target);
            if (!(state.getBlock() instanceof StructuralBlock product) || !PlacementCapture.ownProduct(product)
                    || !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(product.asItem()).toString().equals(placement.product())
                    || state != product.defaultBlockState().setValue(StructuralBlock.AXIS,StructuralBlock.Axis.values()[placement.axis()+1])
                    || before.get(target).hasBlockEntity()) throw new IOException("BUILD product mismatch");
            var declaration = new com.blockreality.core.world.ConstructionDeclaration(product.materialToken(),product.sectionToken(),placement.axis());
            if (context.pieces().size()!=1 || !context.pieces().get(0).declaration().equals(declaration)
                    || !context.pieces().get(0).cells().equals(List.of(new BlockKey(target.getX(),target.getY(),target.getZ()))))
                throw new IOException("BUILD identity does not match the declared cell");
            Value itemAfter;
            var directory = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            if (placement.creative()) {
                if (actor != null) materialBefore=LivePlayerInventory.image(actor.getInventory().getItem(placement.slot()));
                else {
                    var saved = new PlayerFileParticipant(directory).capture(request.actor());
                    if (!saved.exists()) throw new IOException("Creative recovery baseline is missing");
                    materialBefore=PlayerInventoryImage.read(saved.data(),placement.slot());
                }
                itemAfter=materialBefore;
            } else {
                Change debit=changes.get(inventoryKey);
                if (debit==null) throw new IOException("BUILD material participant is missing");
                materialBefore=debit.before();itemAfter=debit.after();
                if (!LivePlayerInventory.debitOne(materialBefore).equals(itemAfter)) throw new IOException("BUILD must debit exactly one material");
            }
            var item=LivePlayerInventory.decode(materialBefore);
            if (item.isEmpty() || item.getItem()!=product.asItem()) throw new IOException("BUILD material does not match the product");
            placement.verifyImages(CellStateImage.encode(before.get(target)),CellStateImage.encode(state),materialBefore,itemAfter);
            inventory=new PlayerInventoryParticipant(directory,request.actor(),actor,placement.slot(),materialBefore,itemAfter);
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
            if (key.equals(inventoryKey)) return inventory.read();
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
            requireResources(resources);
            StructureManager.of(level).checkpointConstructionCoverage(level,before.keySet());
            persistCurrent();
            if (inventory!=null) inventory.checkpoint();
            checkAccess();
        }
        @Override public void write(String key, Value value) throws IOException {
            checkAccess(); Change change = changes.get(key);
            if (change == null || !value.equals(change.before()) && !value.equals(change.after())) throw new IOException("Foreign construction write");
            if (key.equals(inventoryKey)) { inventory.write(value); return; }
            if (working.containsKey(key)) { working.put(key,value); return; }
            BlockPos pos = CellStateImage.position(key); BlockState desired = CellStateImage.decode(value);
            boolean validate = placement!=null && !recovering && value.equals(change.after());
            net.minecraftforge.common.util.BlockSnapshot snapshot = validate
                    ? net.minecraftforge.common.util.BlockSnapshot.create(level.dimension(),level,pos,16 | 32) : null;
            if (readCell(pos) != desired && !level.setBlock(pos,desired,16 | 32))
                throw new IOException("Construction cell write refused");
            if (readCell(pos) != desired) throw new IOException("Construction cell write mismatch");
            if (validate) {
                if (actor==null || !inventory.read().equals(materialBefore)) throw new IOException("Placement protection must precede material debit");
                validatingPlacement=snapshot;validatingActor=actor;
                try {
                    if (net.minecraftforge.event.ForgeEventFactory.onBlockPlace(actor,snapshot,placement.face()))
                        throw new IOException("Placement protection refused");
                    inventory.requireKnownInventory();checkAccess();
                } finally { validatingPlacement=null;validatingActor=null; }
            }
        }
        @Override public void flush(List<String> resources) throws IOException {
            requireResources(resources); persistCurrent();
            if (inventory!=null) inventory.flush();
            checkAccess();
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
            if (inventory!=null) inventory.requireKnownInventory();
            metadata.applyCommitted(journal,receipt.request().id());
            publishing = true;
            try {
                StructureManager.of(level).publishConstructionState(level,after,receipt.request().baseRevision());
                for (var cell : after.entrySet()) level.sendBlockUpdated(cell.getKey(),before.get(cell.getKey()),cell.getValue(),2);
                StructureManager.of(level).announceConstruction(level,receipt.revision());
            } finally { publishing = false; }
            if (placement!=null && actor!=null) committedPlacementEffects=() -> {
                actor.inventoryMenu.broadcastChanges();
                BlockState state=after.get(placement.target());
                BlockState prior=before.get(placement.target());
                // Vanilla adjacency notifications run only after the private owner is released.
                // They may start normal, separately observed game changes; they are not engine physics.
                NeighborPublication previous=neighborPublication;
                neighborPublication=new NeighborPublication(level,placement.target(),state);
                try {level.blockUpdated(placement.target(),prior.getBlock());}
                finally {neighborPublication=previous;}
                if(state.hasAnalogOutputSignal())level.updateNeighbourForOutputSignal(placement.target(),state.getBlock());
                prior.updateIndirectNeighbourShapes(level,placement.target(),2,512);
                state.updateNeighbourShapes(level,placement.target(),2,512);
                state.updateIndirectNeighbourShapes(level,placement.target(),2,512);
                var sound=state.getSoundType(level,placement.target(),actor);
                level.playSound(null,placement.target(),sound.getPlaceSound(),net.minecraft.sounds.SoundSource.BLOCKS,
                        (sound.getVolume()+1.0f)/2.0f,sound.getPitch()*0.8f);
                actor.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(state.getBlock().asItem()));
                try {net.minecraft.advancements.CriteriaTriggers.PLACED_BLOCK.trigger(actor,placement.target(),LivePlayerInventory.decode(materialBefore));}
                catch(IOException invalid) {throw new IllegalStateException("Committed material image became unreadable",invalid);}
                level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_PLACE,placement.target(),
                        net.minecraft.world.level.gameevent.GameEvent.Context.of(actor,state));
            };
        }
        @Override public void publishRecoveredState() throws IOException {
            checkAccess();
            if (prepared != null) metadata = ManufacturedRegistry.load(journal); // Includes the newly durable ABORTED floor.
            for (ServerLevel dimension : server.getAllLevels()) {
                String id = dimension.dimension().location().toString();
                StructureManager.of(dimension).restoreConstructionBaseline(dimension,metadata.worldRevisionFloor(id),metadata.ownedCells(id));
            }
        }
    }
}
