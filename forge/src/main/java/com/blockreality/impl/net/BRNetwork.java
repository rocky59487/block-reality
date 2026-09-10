package com.blockreality.impl.net;

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
 * Analysis delivery is server to client only. Construction requests use the separate
 * {@link ConstructionChannel}, with their own admission, protection checks and wire version.
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
    // "5": ...and the buckling STATE in place of the skipped flag, plus a per-reason
    //      tally of the blocks left out of the model (N17/N18). Same note applies:
    //      nothing released speaks "4" or "5", so this is still one unshipped step.
    // "6": shell recovery samples and authoritative flags replace the reconstructed field.
    // "7": beam samples, full cells and governing station identity replace force reconstruction.
    // "8": precise station side identities. "9": f64 global values and supplied verdicts.
    // "10": bounded whole-element delivery and explicit governing omission.
    // "11": one source/sequence/world-revision envelope for results and notices.
    private static final String PROTOCOL = "11";
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BlockRealityMod.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private BRNetwork() { }

    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CHANNEL.registerMessage(0, AnalysisUpdatePacket.class,
                    AnalysisUpdatePacket::encode, AnalysisUpdatePacket::decode, AnalysisUpdatePacket::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        });
    }

    /** Shared across dimensions so a client can retain its ordering watermark during travel. */
    public static long nextSequence() { return SEQUENCE.incrementAndGet(); }

    public static void broadcast(ServerLevel level, AnalysisUpdatePacket packet) {
        if (!packet.valid()) {
            BlockRealityMod.LOG.warn("refusing invalid analysis update: {}", packet.invalidReason());
            return;
        }
        for (ServerPlayer player : level.players()) sendTo(player, packet);
    }

    public static void sendTo(ServerPlayer player, AnalysisUpdatePacket packet) {
        if (!packet.valid()) {
            BlockRealityMod.LOG.warn("refusing invalid analysis snapshot: {}", packet.invalidReason());
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
