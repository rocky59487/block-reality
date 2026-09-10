package com.blockreality.impl.net;

import com.blockreality.core.transaction.PendingWork;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.server.construction.ConstructionService;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;
import java.util.function.Supplier;
import static com.blockreality.impl.net.ConstructionProtocol.*;

/** Explicit construction direction/size/admission boundary, separate from the unchanged analysis wire. */
public final class ConstructionChannel {
    private static final String PROTOCOL="1";
    public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BlockRealityMod.MOD_ID,"construction"),()->PROTOCOL,PROTOCOL::equals,PROTOCOL::equals);
    private static final PendingWork<Connection> TO_SERVER=new PendingWork<>(4,128);
    private static final PendingWork<Connection> TO_CLIENT=new PendingWork<>(16,64);
    private ConstructionChannel() { }
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(()-> {
            CHANNEL.registerMessage(0,Preview.class,ConstructionProtocol::encodePreview,ConstructionProtocol::decodePreview,
                    (message,context)->server(context,actor->ConstructionService.preview(actor,message)),Optional.of(NetworkDirection.PLAY_TO_SERVER));
            CHANNEL.registerMessage(1,Confirmation.class,ConstructionProtocol::encodeConfirmation,ConstructionProtocol::decodeConfirmation,
                    (message,context)->server(context,actor->ConstructionService.confirm(actor,message)),Optional.of(NetworkDirection.PLAY_TO_SERVER));
            CHANNEL.registerMessage(2,OfferReply.class,ConstructionProtocol::encodeOffer,ConstructionProtocol::decodeOffer,
                    (message,context)->client(context,connection->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            ()->()->com.blockreality.impl.client.ClientConstruction.offer(message,connection))),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            CHANNEL.registerMessage(3,Outcome.class,ConstructionProtocol::encodeOutcome,ConstructionProtocol::decodeOutcome,
                    (message,context)->client(context,connection->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            ()->()->com.blockreality.impl.client.ClientConstruction.outcome(message,connection))),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        });
    }
    private static void server(Supplier<NetworkEvent.Context> supplier,java.util.function.Function<net.minecraft.server.level.ServerPlayer,Object> action) {
        NetworkEvent.Context context=supplier.get();context.setPacketHandled(true);
        if(context.getDirection()!=NetworkDirection.PLAY_TO_SERVER)return;
        var actor=context.getSender();Connection connection=context.getNetworkManager();
        if(actor==null || actor.connection.connection!=connection)return;
        enqueue(context,TO_SERVER,()-> {
            if(actor.isRemoved() || actor.connection.connection!=connection || !connection.isConnected())return;
            Object reply=action.apply(actor);
            CHANNEL.send(PacketDistributor.PLAYER.with(()->actor),reply);
        });
    }
    private static void client(Supplier<NetworkEvent.Context> supplier,java.util.function.Consumer<Connection> action) {
        NetworkEvent.Context context=supplier.get();context.setPacketHandled(true);
        if(context.getDirection()!=NetworkDirection.PLAY_TO_CLIENT)return;
        enqueue(context,TO_CLIENT,()->action.accept(context.getNetworkManager()));
    }
    private static void enqueue(NetworkEvent.Context context,PendingWork<Connection> queue,Runnable work) {
        var lease=queue.acquire(context.getNetworkManager());
        if(lease==null)return;
        try {
            context.enqueueWork(()-> {try {work.run();} finally {lease.close();}})
                    .whenComplete((ignored,failure)->lease.close());
        } catch(RuntimeException|Error failure) {lease.close();throw failure;}
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {TO_SERVER.clear();}
    public static void clearClientQueue() {TO_CLIENT.clear();}
}
