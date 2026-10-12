package com.blockreality.impl.server.finite;

import com.blockreality.core.bsi.BsiContract;
import com.blockreality.core.bsi.BsiCorot;
import com.blockreality.core.bsi.BsiCorotFracture;
import com.blockreality.core.bsi.BsiHeaders;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.diagnostics.PipelineProfile;
import com.blockreality.core.engine.GameNativeLoader;
import com.blockreality.core.engine.GameVocabulary;
import com.blockreality.core.engine.InProcessEngine;
import com.blockreality.core.finite.ArtifactBinding;
import com.blockreality.core.finite.FiniteDomainId;
import com.blockreality.core.finite.FiniteLane;
import com.blockreality.core.finite.FiniteSource;
import com.blockreality.core.json.JsonValue;
import com.blockreality.core.transaction.ConstructionTransaction.Phase;
import com.blockreality.core.transaction.ConstructionTransaction.Receipt;
import com.blockreality.impl.BRConfig;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.server.construction.ConstructionService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The W4-G finite domain entry (docs/FINITE_DOMAIN_ENTRY.md): the only gated in-game
 * path that carries a configured test dimension from "adopted or not" to a committed
 * finite source — adopt → bind unbound manufactured pieces → observe the live world →
 * reuse-or-mint the durable vocabulary → {@code commitSource}. There is no solve, no
 * qualification packet and no fracture here; nothing calls the entry except the
 * explicit operator command.
 *
 * <p>Every native call stays on a lane thread: genesis vocabulary minting runs on a
 * private marshaling lane ({@code br-entry-lane-*}, doc §9.5's named synchronous path),
 * and authority sync rides the runtime's {@code br-finite-lane-*} worker — the server
 * thread never executes JNA and never joins a worker future.
 */
public final class FiniteEntryService {

    private static final Map<MinecraftServer, FiniteEntryService> SERVERS = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private final UUID session = UUID.randomUUID();
    private volatile Core core;
    private volatile String failure = "";

    private FiniteEntryService(MinecraftServer server) { this.server = server; }

    // ---------------------------------------------------------------- Forge lifecycle

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void aboutToStart(ServerAboutToStartEvent event) {
        FiniteEntryService service = new FiniteEntryService(event.getServer());
        if (SERVERS.putIfAbsent(event.getServer(), service) != null)
            throw new IllegalStateException("Finite entry service already owns a core");
        service.openCore();
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        FiniteEntryService service = SERVERS.remove(event.getServer());
        if (service != null) service.closeCore();
    }

    // ---------------------------------------------------------------- core wiring

    private void openCore() {
        ConstructionService construction = ConstructionService.of(server);
        FiniteRuntime runtime = ForgeFiniteService.runtime(server);
        if (construction == null || construction.journal() == null) {
            failure = "Construction journal is unavailable; finite entry not opened";
            BlockRealityMod.LOG.error(failure);
            return;
        }
        if (runtime == null) {
            failure = "Finite runtime is unavailable; finite entry not opened";
            BlockRealityMod.LOG.error(failure);
            return;
        }
        core = new Core(runtime, this::levelWorld, laneSource(log()),
                this::entryGuard, admittedDimensions(), log(), session);
    }

    private void closeCore() {
        Core current = core;
        core = null;
        if (current != null) current.close();
        if (failure.isEmpty()) failure = "Finite entry service closed";
    }

    private FiniteObservation.World levelWorld(String dimension) throws IOException {
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        if (location == null) throw new IOException("Finite dimension is malformed " + dimension);
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
        if (level == null) throw new IOException("Finite dimension is not loaded " + dimension);
        return new LevelFiniteWorld(level);
    }

    private static ForgeFiniteFractureHost.LaneSource laneSource(Consumer<String> log) {
        return () -> {
            InProcessEngine engine = GameNativeLoader.openEngine(BRConfig.INSTANCE.enginePath.get(),
                    FMLPaths.GAMEDIR.get().resolve("blockreality/engine"),
                    BRConfig.INSTANCE.numThreads.get(), log, PipelineProfile.disabled());
            return new ForgeFiniteFractureHost.OpenedLane(FiniteLane.inProcess(engine), engine,
                    engine.engineName() + "/" + engine.engineVersion(), BsiContract.sha256());
        };
    }

    private static Set<String> admittedDimensions() {
        var admitted = new HashSet<String>();
        for (String dimension : BRConfig.INSTANCE.finiteTestDimensions.get()) admitted.add(dimension);
        return Set.copyOf(admitted);
    }

    private void checkThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Finite entry requires the server thread");
    }

    /** Admission for finite entry points: server thread plus construction + finite available. */
    private void entryGuard() {
        checkThread();
        ConstructionService construction = ConstructionService.of(server);
        FiniteRuntime runtime = ForgeFiniteService.runtime(server);
        if (construction == null || !construction.availableInstance()
                || runtime == null || !runtime.available())
            throw new IllegalStateException("Finite entry service is not available");
    }

    private Consumer<String> log() {
        return message -> BlockRealityMod.LOG.info("[finite-entry] {}", message);
    }

    // ---------------------------------------------------------------- public API

    public static @Nullable FiniteEntryService of(MinecraftServer server) { return SERVERS.get(server); }

    public static @Nonnull String failure(MinecraftServer server) {
        FiniteEntryService service = SERVERS.get(server);
        if (service == null) return "Finite entry service has not started";
        Core current = service.core;
        if (!service.failure.isEmpty()) return service.failure;
        return current == null ? "Finite entry service not opened" : current.failure();
    }

    /**
     * The explicit test-domain bootstrap entry (docs/FINITE_DOMAIN_ENTRY.md §2): adopt
     * the dimension, bind every unbound manufactured piece, observe the live world,
     * reuse-or-mint the durable vocabulary, and commit one source. There is no default
     * trigger — nothing calls this except the gated in-game command.
     */
    public static @Nonnull Bootstrap bootstrap(ServerLevel level) throws IOException {
        Objects.requireNonNull(level);
        FiniteEntryService service = requireService(level.getServer());
        return service.core.bootstrap(level.dimension().location().toString());
    }

    /** A read-only snapshot of one dimension's finite domain state. */
    public static @Nonnull FiniteStatus status(ServerLevel level) throws IOException {
        Objects.requireNonNull(level);
        FiniteEntryService service = requireService(level.getServer());
        return service.core.status(level.dimension().location().toString());
    }

    /**
     * The gated fracture trigger (docs/FINITE_DOMAIN_ENTRY.md §6): resolve this
     * dimension's committed domain, decode the durable vocabulary for the named
     * material's id, and hand one failure rule to {@link FiniteFractureService#fracture}.
     * A domain with no committed source is named-refused — fracture cannot run against
     * nothing. Caller-controlled ids are not accepted here; each command mints a fresh
     * request id, while the service replays a durable decision on a repeated id.
     */
    public static @Nonnull Receipt fracture(ServerLevel level, String material,
                                            double damageT, double damageC, int budget)
            throws IOException {
        Objects.requireNonNull(level);
        Objects.requireNonNull(material);
        FiniteEntryService service = requireService(level.getServer());
        String dimension = level.dimension().location().toString();
        FiniteDomainId domain = service.core.requireSourcedDomain(dimension);
        int materialId = service.core.fractureMaterialId(domain, material);
        var rules = List.of(new BsiCorotFracture.Rule(materialId, 0L, 0.0, damageT, damageC, 0.0));
        return FiniteFractureService.fracture(level, domain, rules, budget, UUID.randomUUID());
    }

    private static FiniteEntryService requireService(MinecraftServer server) {
        FiniteEntryService service = SERVERS.get(server);
        if (service == null || service.core == null)
            throw new IllegalStateException("Finite entry service is not available");
        service.entryGuard();
        return service;
    }

    // ---------------------------------------------------------------- results

    /** The bootstrap answer: a committed source, a durable refusal, or an honest waiting state. */
    public sealed interface Bootstrap {
        record Committed(FiniteDomainId domain, int boundPieces, int supports,
                         Receipt receipt, long worldRevision) implements Bootstrap { }
        record Refused(FiniteDomainId domain, String reason, @Nullable Receipt receipt)
                implements Bootstrap { }
        record Waiting(FiniteDomainId domain, int boundPieces, int missingChunks)
                implements Bootstrap { }
    }

    /** The read-only finite-domain snapshot the status command prints. */
    public record FiniteStatus(String dimension, boolean admitted, boolean adopted,
                               FiniteRuntime.Observation observation, int bindings, int boundCells,
                               long worldRevision, long historySequence, int sourceBlocks,
                               @Nullable FiniteLaneWorker.LaneEvent laneEvent) { }

    // ---------------------------------------------------------------- MC-free kernel

    /**
     * Every entry decision with no Minecraft types. Tests wire it to a recording
     * observation world and a scripted lane exactly like {@code ForgeFiniteRuntimeTest}
     * wires the runtime.
     */
    static final class Core implements AutoCloseable {

        /** A read-only observation view per dimension; the adapter never loads chunks. */
        interface Worlds {
            FiniteObservation.World world(String dimension) throws IOException;
        }

        private final FiniteRuntime runtime;
        private final Worlds worlds;
        private final ForgeFiniteFractureHost.LaneSource lanes;
        private final Runnable entryGuard;
        private final Set<String> admittedDimensions;
        private final Consumer<String> log;
        private final UUID session;
        private volatile String failure = "";
        private volatile boolean closed;

        Core(FiniteRuntime runtime, Worlds worlds,
             ForgeFiniteFractureHost.LaneSource lanes, Runnable entryGuard,
             Set<String> admittedDimensions, Consumer<String> log, UUID session) {
            this.runtime = Objects.requireNonNull(runtime);
            this.worlds = Objects.requireNonNull(worlds);
            this.lanes = Objects.requireNonNull(lanes);
            this.entryGuard = Objects.requireNonNull(entryGuard);
            this.admittedDimensions = Set.copyOf(Objects.requireNonNull(admittedDimensions));
            this.log = Objects.requireNonNull(log);
            this.session = Objects.requireNonNull(session);
        }

        /** The §2 sequence — adopt, bind, observe, reuse-or-mint vocabulary, commit. */
        Bootstrap bootstrap(String dimension) throws IOException {
            Objects.requireNonNull(dimension);
            entryGuard.run();
            if (closed || !failure.isEmpty())
                throw new IllegalStateException("Finite entry service is not available");
            if (!admittedDimensions.contains(dimension))
                throw new IOException("Dimension is not an admitted finite test domain " + dimension);
            FiniteDomainId domain = runtime.adoptDomain(dimension);
            int boundPieces = 0;
            var plans = runtime.unboundPlans(domain);
            if (!plans.isEmpty()) {
                Receipt bind = runtime.bindArtifacts(domain, plans);
                if (bind.phase() != Phase.COMMITTED)
                    return new Bootstrap.Refused(domain, "Artifact bind refused: " + bind.reason(), bind);
                boundPieces = plans.size();
            }
            var bindings = runtime.activeBindings(domain);
            if (bindings.isEmpty())
                throw new IOException("Finite domain has no bound cells " + dimension);
            var gathered = FiniteObservation.gather(bindings, worlds.world(dimension));
            if (gathered.drifted())
                throw new IOException("Finite world drifted: " + String.join("; ", gathered.drift()));
            if (!gathered.observation().complete()) {
                runtime.markWaiting(domain, gathered.missingChunks());
                return new Bootstrap.Waiting(domain, boundPieces, gathered.missingChunks());
            }
            FiniteSource committed = runtime.source(domain).orElse(null);
            String vocabRequest, vocabReply;
            if (committed == null) {
                // Refuse unsupported material roles before any lane is opened.
                checkMaterialRoles(bindings, GameVocabulary.declaration());
                var minted = mintVocabulary(dimension);
                vocabRequest = minted.requestJson();
                vocabReply = minted.replyJson();
            } else {
                vocabRequest = committed.vocabRequestJson();
                vocabReply = committed.vocabReplyJson();
                checkMaterialRoles(bindings, vocabRequest);
            }
            var spec = buildSpec(vocabRequest, vocabReply, bindings);
            var outcome = runtime.commitSource(domain, spec, gathered.observation());
            if (outcome instanceof FiniteRuntime.CommitOutcome.Decided decided
                    && decided.receipt().phase() == Phase.COMMITTED) {
                long revision = runtime.root(domain).orElseThrow(
                        () -> new IOException("Committed finite root missing after publish")).worldRevision();
                log.accept("bootstrap committed " + dimension + " rev " + revision
                        + " pieces " + boundPieces + " supports " + gathered.supports().size());
                return new Bootstrap.Committed(domain, boundPieces, gathered.supports().size(),
                        decided.receipt(), revision);
            }
            Receipt receipt = outcome instanceof FiniteRuntime.CommitOutcome.Decided decided
                    ? decided.receipt() : null;
            return new Bootstrap.Refused(domain, receipt == null
                    ? "Source commit produced no durable decision"
                    : "Source commit refused: " + receipt.reason(), receipt);
        }

        /** Read-only snapshot — the same queries the runtime exposes, nothing more. */
        FiniteStatus status(String dimension) {
            Objects.requireNonNull(dimension);
            entryGuard.run();
            if (closed || !failure.isEmpty())
                throw new IllegalStateException("Finite entry service is not available");
            boolean admitted = admittedDimensions.contains(dimension);
            var domain = runtime.domain(dimension);
            var bindings = domain.isPresent() ? runtime.activeBindings(domain.get().id()) : List.<ArtifactBinding>of();
            int boundCells = 0;
            for (ArtifactBinding binding : bindings) boundCells += binding.cells().size();
            long revision = -1, sequence = -1;
            int sourceBlocks = -1;
            if (domain.isPresent()) {
                var root = runtime.root(domain.get().id());
                if (root.isPresent()) {
                    revision = root.get().worldRevision();
                    sequence = root.get().historySequence();
                }
                try {
                    var source = runtime.source(domain.get().id());
                    if (source.isPresent()) sourceBlocks = source.get().blocks().size();
                } catch (IOException problem) {
                    log.accept("status could not read committed source: " + problem.getMessage());
                }
            }
            return new FiniteStatus(dimension, admitted, domain.isPresent(),
                    runtime.observation(dimension), bindings.size(), boundCells,
                    revision, sequence, sourceBlocks, runtime.laneStatus(dimension));
        }

        // ------------------------------------------------------------ vocabulary + spec

        private record MintedVocabulary(String requestJson, String replyJson) { }

        /**
         * Mint the durable vocabulary pair once, on a private marshaling lane (doc §9.5):
         * the engine's {@code bsi.vocab.declare} answer is the reply the source persists.
         * The lane dies with the mint — it is never the authority session's lane.
         */
        private MintedVocabulary mintVocabulary(String dimension) throws IOException {
            String requestJson = GameVocabulary.declaration();
            int version = (int) JsonValue.parseStrict(requestJson).exactI64("version");
            String requestId = "v" + session;
            try (var lane = ForgeFiniteFractureHost.PrivateLane.openNamed(
                    "br-entry-lane-" + dimension.replace(':', '-'), lanes)) {
                if (!lane.ready())
                    throw new IOException("Finite entry lane is not ready");
                if (!lane.has("bsi.corot"))
                    throw new IOException("Engine lacks bsi.corot; a finite domain cannot run there");
                BsiResponse reply = lane.request(BsiHeaders.vocabDeclare(requestId, 0, requestJson), null);
                var decoded = BsiVocabulary.decodeReply(reply, requestId, 0, version);
                return new MintedVocabulary(requestJson, decoded.headerText());
            } catch (IOException laneFailure) {
                throw laneFailure;
            } catch (RuntimeException problem) {
                throw new IOException("Vocabulary mint refused: " + problem.getMessage(), problem);
            }
        }

        /**
         * Refuse bound wire materials whose durable-vocabulary role cannot produce a
         * certified law record — panels and unknown roles are named, never invented.
         */
        private static void checkMaterialRoles(List<ArtifactBinding> bindings, String vocabRequestJson)
                throws IOException {
            var catalog = JsonValue.parseStrict(vocabRequestJson);
            var panels = new TreeSet<String>();
            for (ArtifactBinding binding : bindings) {
                String material = wireMaterial(binding);
                JsonValue row = materialRow(catalog, material);
                String role = row.str("role", "");
                if ("panel".equals(role)) {
                    panels.add(material);
                } else if (!"member".equals(role) && !"monolith".equals(role)) {
                    throw new IOException("Bound material '" + material
                            + "' has unsupported role '" + role + "'");
                }
            }
            if (!panels.isEmpty())
                throw new IOException("Bound panel materials have no certified shell law records: " + panels);
        }

        /**
         * Assemble the commit spec: one kind-0 (elastic) 152-byte law record per bound
         * wire material — the only material-law shape the engine has proven — with
         * E/σT/σC read from the durable vocabulary request and every inelastic and
         * concrete-law field zeroed (the engine-verified fixture shape).
         */
        private static FiniteDomainSource.Spec buildSpec(String vocabRequestJson, String vocabReplyJson,
                                                         List<ArtifactBinding> bindings) throws IOException {
            int version = (int) JsonValue.parseStrict(vocabRequestJson).exactI64("version");
            var vocabulary = BsiVocabulary.decodeStored(JsonValue.parseStrict(vocabReplyJson), version);
            var catalog = JsonValue.parseStrict(vocabRequestJson);
            var records = new TreeMap<Integer, byte[]>();
            for (ArtifactBinding binding : bindings) {
                String material = wireMaterial(binding);
                int materialId = vocabulary.materialId(material);
                records.putIfAbsent(materialId, material152(materialId, materialRow(catalog, material)));
            }
            byte[] materials;
            try (var out = new java.io.ByteArrayOutputStream()) {
                for (byte[] record : records.values()) out.write(record, 0, record.length);
                materials = out.toByteArray();
            }
            return new FiniteDomainSource.Spec(vocabRequestJson, vocabReplyJson, "ground_rigid",
                    materials, new byte[0], new byte[0], new byte[0], new byte[0], new byte[0], OPTIONS);
        }

        private static String wireMaterial(ArtifactBinding binding) throws IOException {
            try {
                return GameVocabulary.binding(binding.declaration().material(),
                        binding.declaration().section()).material();
            } catch (IllegalArgumentException problem) {
                throw new IOException("Bound product is not in the game vocabulary "
                        + binding.durableId(), problem);
            }
        }

        private static JsonValue materialRow(JsonValue catalog, String material) throws IOException {
            for (JsonValue row : catalog.arr("materials"))
                if (material.equals(row.str("name", ""))) return row;
            throw new IOException("Bound material is not in the durable vocabulary " + material);
        }

        /** The engine-verified kind-0 law record: E/σT/σC positive, inelastic fields zeroed. */
        private static byte[] material152(int materialId, JsonValue row) throws IOException {
            double e = lawValue(row, "E", null);
            double tension = lawValue(row, "sigmaT", "allow");
            double compression = lawValue(row, "sigmaC", "allow");
            var b = ByteBuffer.allocate(BsiCorot.MATERIAL_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(materialId).putInt(0);                       // material id + reserved
            b.putInt(0).putInt(0);                              // primary law: kind=0 elastic
            b.putDouble(e).putDouble(tension).putDouble(compression);
            b.putDouble(0).putDouble(0).putDouble(0).putDouble(0).putDouble(0);  // inelastic fields
            // the concrete-law half (offset 80..152) stays zeroed — the proven fixture shape
            return b.array();
        }

        private static double lawValue(JsonValue row, String key, @Nullable String nest)
                throws IOException {
            JsonValue holder = nest == null ? row : row.objField(nest);
            if (!holder.isFiniteNum(key) || holder.num(key, 0) <= 0)
                throw new IOException("Durable vocabulary material '" + row.str("name", "")
                        + "' lacks certified law data " + key);
            return holder.num(key, 0);
        }

        /** The frozen options template (doc §4); the domain/revision stamp is re-derived at commit. */
        private static final BsiCorot.Options OPTIONS = new BsiCorot.Options(new UUID(0, 0), 0,
                1.0, new double[] {0, -9.81, 0}, 1,
                1e-4, 1e-3, 1e-3, 1.0, 1e-6, 1.0, 1e-8, 1e-4, 1 << 20, 0,
                50, 4, 8, 25, 1, 4096, 8, 2, 8);

        /**
         * The fracture entry's domain check: admitted dimension plus adopted domain plus
         * a committed source — nothing else can carry a fracture transaction.
         */
        FiniteDomainId requireSourcedDomain(String dimension) throws IOException {
            entryGuard.run();
            if (closed || !failure.isEmpty())
                throw new IllegalStateException("Finite entry service is not available");
            if (!admittedDimensions.contains(dimension))
                throw new IOException("Dimension is not an admitted finite test domain " + dimension);
            var domain = runtime.domain(dimension).orElseThrow(
                    () -> new IOException("Dimension has no adopted finite domain " + dimension));
            if (runtime.root(domain.id()).isEmpty())
                throw new IOException("Finite domain has no committed source; bootstrap first "
                        + dimension);
            return domain.id();
        }

        /**
         * Resolve a material name against the committed source's durable vocabulary —
         * wire ids are engine-assigned, never hard-coded.
         */
        int fractureMaterialId(FiniteDomainId domain, String material) throws IOException {
            FiniteSource source = runtime.source(domain).orElseThrow(
                    () -> new IOException("Committed finite source is unreadable"));
            int version = (int) JsonValue.parseStrict(source.vocabRequestJson()).exactI64("version");
            var vocabulary =
                    BsiVocabulary.decodeStored(JsonValue.parseStrict(source.vocabReplyJson()), version);
            try {
                return vocabulary.materialId(material);
            } catch (IllegalArgumentException missing) {
                throw new IOException("Material '" + material
                        + "' is not in the durable vocabulary", missing);
            }
        }

        public String failure() { return failure; }
        @Override public void close() { closed = true; }
    }
}
