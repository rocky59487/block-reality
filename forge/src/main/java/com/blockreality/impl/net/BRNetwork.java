package com.blockreality.impl.net;

import com.blockreality.api.AnalysisResult;
import com.blockreality.core.sidecar.SidecarClient;
import com.blockreality.impl.BlockRealityMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Server to client only.
 *
 * <p>The demo has <strong>no</strong> client-to-server packets. Every player action that
 * matters — placing a block, breaking one, toggling a load — already reaches the server
 * through a vanilla event that vanilla has already permission-checked. Adding a custom
 * C2S packet would mean re-implementing those checks, and the previous codebase's audit
 * found a batch-placement path that skipped {@code EntityPlaceEvent} and the world border
 * entirely, bypassing every land-claim protection in the pack.
 *
 * <p>So the smallest safe surface is no surface. When a C2S packet does become necessary,
 * it needs a rate limit and a size cap on day one, because retrofitting those means
 * auditing every call site instead of one.
 *
 * <p>"S2C only" is <em>declared</em>, not assumed: every registration pins
 * {@link NetworkDirection#PLAY_TO_CLIENT}. Without the pin, a modified client could send
 * these packets to the server, where the handler would run on the server thread
 * (FORGE-4) — harmless for today's handlers, which are client-only behind DistExecutor,
 * but the registration should enforce the sentence above rather than trust it.
 */
public final class BRNetwork {

    /** Bumped when the packet layout changes; 2 = classification flags + dimension. */
    // "3": StressResultPacket gained bucklingSkipped (v0.4 mod-side round 1)
    // "4": ...and truncatedBlocks plus a per-element withheld flag (N14, #74),
    //      and the material token, which the decoder used to drop on the floor.
    //      One number for both: v0.3c shipped "3", so nothing released speaks "4" yet.
    private static final String PROTOCOL = "4";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BlockRealityMod.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private BRNetwork() { }

    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            int id = 0;
            CHANNEL.registerMessage(id++, StressResultPacket.class,
                    StressResultPacket::encode, StressResultPacket::decode,
                    StressResultPacket::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            CHANNEL.registerMessage(id++, EngineStatusPacket.class,
                    EngineStatusPacket::encode, EngineStatusPacket::decode,
                    EngineStatusPacket::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            CHANNEL.registerMessage(id++, AnalysisPendingPacket.class,
                    AnalysisPendingPacket::encode, AnalysisPendingPacket::decode,
                    AnalysisPendingPacket::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        });
    }

    /**
     * Broadcasts a result to everyone in the dimension.
     *
     * <p>Per-player culling by distance is the obvious next step and is not done here: the
     * demo is one structure, and a distance filter that is wrong is harder to notice than
     * one that is absent.
     */
    public static void sendResult(ServerLevel level, AnalysisResult result, boolean bucklingSkipped) {
        sendResult(level, result, bucklingSkipped, java.util.Set.of(), java.util.Set.of(), 0);
    }

    public static void sendResult(ServerLevel level, AnalysisResult result, boolean bucklingSkipped,
                                  java.util.Set<Integer> withheldMembers,
                                  java.util.Set<Integer> withheldShells,
                                  int truncatedBlocks) {
        StressResultPacket packet = StressResultPacket.of(result,
                level.dimension().location().toString(), bucklingSkipped,
                withheldMembers, withheldShells, truncatedBlocks);
        for (ServerPlayer player : level.players()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    public static void sendEngineStatus(ServerLevel level, SidecarClient.Status status, String detail) {
        EngineStatusPacket packet = new EngineStatusPacket(status.name(), detail);
        for (ServerPlayer player : level.players()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    /** The world moved on; clients should mark what they are drawing as stale (INV-4). */
    public static void sendAnalysisPending(ServerLevel level, long revision) {
        AnalysisPendingPacket packet = new AnalysisPendingPacket(
                level.dimension().location().toString(), revision);
        for (ServerPlayer player : level.players()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }
}
