package com.blockreality.impl.net;

import com.blockreality.core.AnalysisDeliveryClock;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.server.StructureManager;
import com.mojang.authlib.GameProfile;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Opt-in integration source: synthetic actors on the isolated server, never a shipped command. */
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID)
public final class StateDeliveryProbe {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("br_delivery_probe").requires(s -> s.hasPermission(4))
                .executes(ctx -> {
                    var server = ctx.getSource().getServer();
                    require("runtime-smoke".equals(server.getWorldData().getLevelName()), "isolated world required");
                    var overworld = server.overworld();
                    var nether = Objects.requireNonNull(server.getLevel(Level.NETHER));
                    var manager = StructureManager.of(overworld);
                    var cached = manager.latest(); long revision = manager.gate().current().value();
                    var clock = new AnalysisDeliveryClock();
                    var connection = new Capture();
                    var a = player(overworld, connection, "DeliveryProbeA");
                    var b = player(nether, connection, "DeliveryProbeB");
                    try {
                        var login = deliver(new PlayerEvent.PlayerLoggedInEvent(a), connection, clock, overworld, false);
                        var respawn = deliver(new PlayerEvent.PlayerRespawnEvent(a, false), connection, clock, overworld, true);
                        var travel = deliver(new PlayerEvent.PlayerChangedDimensionEvent(b, Level.OVERWORLD, Level.NETHER),
                                connection, clock, nether, true);
                        var back = deliver(new PlayerEvent.PlayerChangedDimensionEvent(a, Level.NETHER, Level.OVERWORLD),
                                connection, clock, overworld, true);
                        require(login.sourceId().equals(respawn.sourceId()) && login.sourceId().equals(back.sourceId()), "stable source");
                        require(!login.sourceId().equals(travel.sourceId()), "dimension-specific source");
                        require(cached == manager.latest() && revision == manager.gate().current().value(), "bootstrap changed analysis");
                        if (cached != null && cached.ok()) {
                            require(login.result() != null && login.result().revision() == cached.revision().value(), "cached result missing");
                            require(login.result().overCapacity() == cached.overCapacity(), "capacity flag changed");
                            require(login.result().bucklingCritical() == cached.bucklingCritical(), "buckling flag changed");
                            require(login.result().maxDc() == cached.maxDc(), "D/C changed");
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("delivery probe PASS: login=" + login.kind()
                                + " respawn=" + respawn.kind() + " travel=" + travel.kind() + " return=" + back.kind()
                                + "; cached result unchanged; synthetic actors"), false);
                    } finally {
                        a.getTextFilter().leave(); b.getTextFilter().leave();
                        connection.embedded.finishAndReleaseAll();
                    }
                    return 1;
                }));
    }
    private static ServerPlayer player(ServerLevel level, Capture connection, String name) {
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name));
        new ServerGamePacketListenerImpl(level.getServer(), connection, player);
        return player;
    }
    private static AnalysisUpdatePacket deliver(PlayerEvent event, Capture connection, AnalysisDeliveryClock clock,
            ServerLevel level, boolean transition) {
        if (transition) clock.leaveDimension();
        connection.received.clear();
        MinecraftForge.EVENT_BUS.post(event);
        require(connection.received.size() == 1, "expected exactly one bootstrap from event " + event.getClass().getSimpleName());
        var p = connection.received.get(0);
        require(p.valid() && p.bootstrap(), "valid bootstrap required: " + p.invalidReason());
        require(clock.accept(level.dimension().location().toString(), p.dimension(), p.sourceId(), p.sequence(),
                p.worldRevision(), p.bootstrap(), p.result() == null ? -1 : p.result().revision()), "clock refused event snapshot");
        return p;
    }
    private static final class Capture extends Connection {
        final List<AnalysisUpdatePacket> received = new ArrayList<>();
        final io.netty.channel.embedded.EmbeddedChannel embedded;
        Capture() {
            super(PacketFlow.SERVERBOUND);
            embedded = new io.netty.channel.embedded.EmbeddedChannel(this);
        }
        @Override public void send(Packet<?> packet, PacketSendListener listener) {
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.getIdentifier().toString().equals("blockreality:main")) {
                var b = custom.getData();
                try {
                    require(b.readVarInt() == 0, "channel discriminator");
                    received.add(AnalysisUpdatePacket.decode(b));
                } finally { b.release(); }
            }
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
