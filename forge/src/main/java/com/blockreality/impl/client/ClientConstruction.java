package com.blockreality.impl.client;

import com.blockreality.core.transaction.PendingRequestStore;
import com.blockreality.impl.BlockRealityMod;
import com.blockreality.impl.net.ConstructionChannel;
import com.blockreality.impl.net.ConstructionProtocol;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.blockreality.impl.net.ConstructionProtocol.*;

/** Client intent/receipt state only. World, inventory and permanent identity remain server-owned. */
@OnlyIn(Dist.CLIENT)
public final class ClientConstruction {
    private static Session active;
    private ClientConstruction() { }
    static final class Session {
        final Connection connection;
        final String destination,dimension;
        final UUID actor;
        Preview preview;
        Offer offer;
        Confirmation sent;
        Status status;
        String localError="";
        boolean waiting,terminal;
        Outcome decision;
        long sentAt,offeredAt;
        Session(Connection connection,String destination,String dimension,UUID actor) {
            this.connection=connection;this.destination=destination;this.dimension=dimension;this.actor=actor;
        }
    }
    public static void open(UseOnContext context) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.player!=context.getPlayer() || mc.level==null || mc.getConnection()==null)return;
        Connection connection=mc.getConnection().getConnection();
        try {
            String destination=destination(mc);
            if(active!=null && current(active) && active.sent!=null && !active.terminal) {
                mc.setScreen(new PlacementScreen(active));return;
            }
            Session next=new Session(connection,destination,mc.level.dimension().location().toString(),mc.player.getUUID());
            active=next;
            int axis=switch(context.getClickedFace().getAxis()) {case X->0;case Y->1;case Z->2;};
            next.preview=new Preview(UUID.randomUUID(),next.dimension,context.getClickedPos(),context.getClickedFace(),
                    context.getClickLocation(),context.getHand(),BuiltInRegistries.ITEM.getKey(context.getItemInHand().getItem()).toString(),axis);
            byte[] pending=store().get(destination);
            if(pending!=null) {
                restore(next,pending);
                next.localError="br.build.unresolved";
            }
            mc.setScreen(new PlacementScreen(next));
            if(next.sent==null)refresh(next,next.preview.axis());
        } catch(Exception failure) {
            if(active!=null) {active.localError="br.build.storage_error";active.waiting=false;mc.setScreen(new PlacementScreen(active));}
            BlockRealityMod.LOG.warn("Construction retry record could not be read",failure);
        }
    }
    static void refresh(Session state,int axis) {
        if(!current(state) || state.sent!=null || state.preview==null)return;
        Preview previous=state.preview;
        state.preview=new Preview(UUID.randomUUID(),state.dimension,previous.clicked(),previous.face(),previous.hit(),previous.hand(),previous.product(),axis);
        state.offer=null;state.status=null;state.localError="";state.waiting=true;state.sentAt=now();
        ConstructionChannel.CHANNEL.sendToServer(state.preview);
    }
    static void confirm(Session state) {
        if(!current(state) || state.waiting || state.terminal)return;
        if(state.sent==null) {
            if(state.offer==null || state.status!=Status.READY)return;
            state.sent=state.offer.confirm(UUID.randomUUID());
        }
        try {
            store().put(state.destination,pending(state)); // This barrier must acknowledge before the first network send.
            state.waiting=true;state.localError="";state.status=null;state.sentAt=now();
            ConstructionChannel.CHANNEL.sendToServer(state.sent);
        } catch(Exception failure) {
            state.waiting=false;state.localError="br.build.storage_error";
            BlockRealityMod.LOG.warn("Construction confirmation was retained without sending",failure);
        }
    }
    /** A new preview is explicit and still needs confirmation; client elapsed time cannot reach this path. */
    static void newPreviewAfterExpiry(Session state) {
        if(!current(state) || state.sent==null || state.status!=Status.EXPIRED || state.waiting || state.decision!=null || state.preview==null)return;
        try {
            store().remove(state.destination,pending(state));
            state.sent=null;state.offer=null;state.status=null;state.localError="";
            refresh(state,state.preview.axis());
        } catch(IOException failure) {
            state.localError="br.build.storage_error";
            BlockRealityMod.LOG.warn("Expired construction retry record could not be released",failure);
        }
    }
    public static void offer(OfferReply reply,Connection connection) {
        Session state=active;
        if(state==null || !current(state) || state.connection!=connection || state.sent!=null
                || state.preview==null || !state.preview.query().equals(reply.query()))return;
        if(reply.offer()!=null && (!reply.offer().dimension().equals(state.dimension)
                || !reply.offer().product().equals(state.preview.product()) || reply.offer().axis()!=state.preview.axis()))return;
        state.offer=reply.offer();state.status=reply.status();state.waiting=false;state.localError="";state.offeredAt=now();
    }
    public static void outcome(Outcome reply,Connection connection) {
        Session state=active;
        if(state==null || !current(state) || state.connection!=connection || !reply.request().equals(state.sent))return;
        if(state.terminal)return;
        if(state.decision!=null && !state.decision.equals(reply))return;
        state.waiting=false;state.status=reply.status();state.localError="";
        boolean terminal=switch(reply.status()) {case COMMITTED,ABORTED,REJECTED,STALE->true;default->false;};
        if(terminal) {
            state.decision=reply;
            try {store().remove(state.destination,pending(state));state.terminal=true;}
            catch(IOException failure) {
                state.localError="br.build.receipt_storage_error";
                BlockRealityMod.LOG.warn("Durable construction outcome could not clear its exact retry record",failure);
            }
        }
        Minecraft mc=Minecraft.getInstance();
        if(!(mc.screen instanceof PlacementScreen) && mc.player!=null)mc.player.displayClientMessage(status(state),true);
    }
    /** Expiry and lost responses disable confirmation without inventing a server decision. */
    static void tick(Session state) {
        if(!current(state))return;
        if(state.waiting && now()-state.sentAt>=5000) {state.waiting=false;state.localError=state.sent==null?"br.build.preview_timeout":"br.build.unresolved";}
        if(state.sent==null && state.status==Status.READY && now()-state.offeredAt>=9000) {state.offer=null;state.status=Status.EXPIRED;}
    }
    static Component status(Session state) {
        if(!state.localError.isEmpty())return Component.translatable(state.localError);
        if(state.waiting)return Component.translatable(state.sent==null?"br.build.wait_preview":"br.build.wait_commit");
        if(state.status!=null)return Component.translatable("br.build.status."+state.status.name().toLowerCase(Locale.ROOT));
        return Component.translatable("br.build.unresolved");
    }
    static boolean current(Session state) {
        Minecraft mc=Minecraft.getInstance();
        return state==active && mc.player!=null && mc.level!=null && mc.getConnection()!=null
                && mc.getConnection().getConnection()==state.connection && state.connection.isConnected()
                && mc.player.getUUID().equals(state.actor) && mc.level.dimension().location().toString().equals(state.dimension);
    }
    public static void leave() {
        active=null;ConstructionChannel.clearClientQueue();
        Minecraft mc=Minecraft.getInstance();if(mc.screen instanceof PlacementScreen)mc.setScreen(null);
    }
    static Session ghost() {
        return active!=null && current(active) && Minecraft.getInstance().screen instanceof PlacementScreen
                && active.offer!=null && active.sent==null && active.status==Status.READY ? active:null;
    }
    private static long now() {return net.minecraft.Util.getMillis();}
    private static PendingRequestStore store() {return new PendingRequestStore(Minecraft.getInstance().gameDirectory.toPath().resolve("blockreality/construction"));}
    private static String destination(Minecraft mc)throws IOException {
        String address;
        if(mc.getCurrentServer()!=null)address="remote:"+mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        else if(mc.getSingleplayerServer()!=null)address="local:"+mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        else throw new IOException("No construction destination");
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((address+"\n"+mc.player.getUUID()).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException unavailable) {throw new AssertionError(unavailable);}
    }
    private static byte[] pending(Session state)throws IOException {
        if(state.offer==null || state.sent==null || !state.offer.confirm(state.sent.id()).equals(state.sent))throw new IOException("Invalid pending binding");
        FriendlyByteBuf offer=new FriendlyByteBuf(Unpooled.buffer()),record=new FriendlyByteBuf(Unpooled.buffer());
        try {
            ConstructionProtocol.encodeOffer(new OfferReply(new UUID(0,0),Status.READY,state.offer),offer);
            record.writeInt(offer.readableBytes());record.writeBytes(offer);ConstructionProtocol.encodeConfirmation(state.sent,record);
            if(record.readableBytes()>PendingRequestStore.MAX_REQUEST_BYTES)throw new IOException("Pending request capacity");
            byte[] bytes=new byte[record.readableBytes()];record.readBytes(bytes);return bytes;
        } finally {offer.release();record.release();}
    }
    private static void restore(Session state,byte[] bytes)throws IOException {
        FriendlyByteBuf record=new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            int length=record.readInt();if(length<1 || length>1024 || length>record.readableBytes())throw new IOException("Invalid pending offer length");
            OfferReply reply=ConstructionProtocol.decodeOffer(new FriendlyByteBuf(record.readSlice(length)));
            if(reply.status()!=Status.READY || !reply.query().equals(new UUID(0,0)))throw new IOException("Invalid pending offer");
            state.offer=reply.offer();state.sent=ConstructionProtocol.decodeConfirmation(record);
            if(!Arrays.equals(bytes,pending(state)))throw new IOException("Invalid pending construction record");
        } catch(RuntimeException malformed) {throw new IOException("Invalid pending construction record",malformed);}
        finally {record.release();}
    }
}
