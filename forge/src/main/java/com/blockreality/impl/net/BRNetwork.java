package com.blockreality.impl.net;

import com.blockreality.api.AnalysisResult;
import com.blockreality.core.sidecar.SidecarClient;
import com.blockreality.impl.BlockRealityMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

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
 */
public final class BRNetwork {

    private static final String PROTOCOL = "1";

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
                    StressResultPacket::handle);
            CHANNEL.registerMessage(id++, EngineStatusPacket.class,
                    EngineStatusPacket::encode, EngineStatusPacket::decode,
                    EngineStatusPacket::handle);
        });
    }

    /**
     * Broadcasts a result to everyone in the dimension.
     *
     * <p>Per-player culling by distance is the obvious next step and is not done here: the
     * demo is one structure, and a distance filter that is wrong is harder to notice than
     * one that is absent.
     */
    public static void sendResult(ServerLevel level, AnalysisResult result) {
        StressResultPacket packet = StressResultPacket.of(result);
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
}
