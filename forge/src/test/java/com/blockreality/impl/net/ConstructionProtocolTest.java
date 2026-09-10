package com.blockreality.impl.net;

import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.impl.net.ConstructionProtocol.*;

class ConstructionProtocolTest {
    private static final UUID ID=new UUID(1,2),TOKEN=new UUID(3,4),DOMAIN=new UUID(5,6),SESSION=new UUID(7,8);
    private static final String HASH="ab".repeat(32);
    private static Confirmation confirmation(){return new Confirmation(ID,TOKEN,DOMAIN,SESSION,17,HASH);}
    private static Preview preview(){return new Preview(ID,"minecraft:overworld",new BlockPos(1,80,2),Direction.UP,new Vec3(1.5,81,2.5),InteractionHand.MAIN_HAND,"blockreality:steel_beam",0);}
    @Test void allMessagesRoundTripWithoutChangingBytes() {
        roundTrip(preview(),ConstructionProtocol::encodePreview,ConstructionProtocol::decodePreview);
        roundTrip(confirmation(),ConstructionProtocol::encodeConfirmation,ConstructionProtocol::decodeConfirmation);
        Offer offer=new Offer(TOKEN,DOMAIN,SESSION,17,HASH,"minecraft:overworld",new BlockPos(1,81,2),"blockreality:steel_beam",0,false);
        roundTrip(new OfferReply(ID,Status.READY,offer),ConstructionProtocol::encodeOffer,ConstructionProtocol::decodeOffer);
        roundTrip(new OfferReply(ID,Status.EXPIRED,null),ConstructionProtocol::encodeOffer,ConstructionProtocol::decodeOffer);
        roundTrip(new Outcome(confirmation(),Status.COMMITTED,18,List.of(new UUID(9,10))),ConstructionProtocol::encodeOutcome,ConstructionProtocol::decodeOutcome);
        roundTrip(new Outcome(confirmation(),Status.REJECTED,17,List.of()),ConstructionProtocol::encodeOutcome,ConstructionProtocol::decodeOutcome);
    }
    @Test void opaqueOfferAndEveryConfirmationFieldRemainBound() {
        Confirmation request=confirmation();assertNotEquals(request.bindingHash(),new Confirmation(ID,UUID.randomUUID(),DOMAIN,SESSION,17,HASH).bindingHash());
        assertNotEquals(request.bindingHash(),new Confirmation(ID,TOKEN,DOMAIN,SESSION,17,"cd".repeat(32)).bindingHash());
        assertThrows(IllegalArgumentException.class,()->new Confirmation(ID,TOKEN,DOMAIN,SESSION,Long.MAX_VALUE,HASH));
        assertThrows(IllegalArgumentException.class,()->new Confirmation(ID,TOKEN,DOMAIN,SESSION,17,HASH.toUpperCase(Locale.ROOT)));
        assertThrows(IllegalArgumentException.class,()->new Outcome(request,Status.COMMITTED,17,List.of(ID)));
        assertThrows(IllegalArgumentException.class,()->new Outcome(request,Status.CONFLICT,17,List.of(ID)));
    }
    @Test void trailingOversizedTruncatedAndUnknownEnumsRefuse() {
        for(int fault=0;fault<4;fault++) {
            FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.buffer());encodePreview(preview(),b);
            if(fault==0)b.writeByte(0);if(fault==1)b.writeZero(1024);if(fault==2)b.writerIndex(5);
            if(fault==3)b.setByte(b.writerIndex()-1,255);
            try{assertThrows(RuntimeException.class,()->decodePreview(b));}finally{b.release();}
        }
        FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.buffer());encodeConfirmation(confirmation(),b);b.writeByte(Status.COMMITTED.ordinal());b.writeLong(18);b.writeVarInt(257);
        try{assertThrows(RuntimeException.class,()->decodeOutcome(b));}finally{b.release();}
    }
    @Test void draftDoesNotRetainMutablePositionsOrAcceptNonfiniteHits() {
        BlockPos.MutableBlockPos mutable=new BlockPos.MutableBlockPos(1,80,2);
        Preview p=new Preview(ID,"minecraft:overworld",mutable,Direction.UP,new Vec3(1.5,81,2.5),InteractionHand.MAIN_HAND,"blockreality:steel_beam",0);
        mutable.set(4,90,5);assertEquals(new BlockPos(1,80,2),p.clicked());
        assertThrows(IllegalArgumentException.class,()->new Preview(ID,"minecraft:overworld",p.clicked(),Direction.UP,new Vec3(Double.NaN,81,2.5),p.hand(),p.product(),0));
        assertThrows(IllegalArgumentException.class,()->new OfferReply(ID,Status.COMMITTED,null));
    }
    private static <T>void roundTrip(T value,BiConsumer<T,FriendlyByteBuf> encode,Function<FriendlyByteBuf,T> decode) {
        FriendlyByteBuf a=new FriendlyByteBuf(Unpooled.buffer()),b=new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode.accept(value,a);byte[] before=new byte[a.readableBytes()];a.getBytes(0,before);
            T actual=decode.apply(a);assertEquals(value,actual);encode.accept(actual,b);
            byte[] after=new byte[b.readableBytes()];b.readBytes(after);assertArrayEquals(before,after);
        } finally {a.release();b.release();}
    }
}
