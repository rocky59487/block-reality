package com.blockreality.impl.net;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Construction-only wire vocabulary. No client inventory images, permanent IDs or physical fields are accepted. */
public final class ConstructionProtocol {
    private ConstructionProtocol() { }
    public enum Status { READY, INVALID, PERMISSION, OUT_OF_REACH, CONFLICT, STALE, EXPIRED, BUSY, CAPACITY,
                         RECOVERY, RATE_LIMITED, COMMITTED, ABORTED, REJECTED }
    public record Preview(UUID query, String dimension, BlockPos clicked, Direction face, Vec3 hit,
                          InteractionHand hand, String product, int axis) {
        public Preview {
            Objects.requireNonNull(query); resource(dimension); resource(product); clicked=position(clicked);
            Objects.requireNonNull(face); Objects.requireNonNull(hand); Objects.requireNonNull(hit); ConstructionProtocol.axis(axis);
            if (!Double.isFinite(hit.x) || !Double.isFinite(hit.y) || !Double.isFinite(hit.z)
                    || hit.x<clicked.getX() || hit.x>clicked.getX()+1.0 || hit.y<clicked.getY() || hit.y>clicked.getY()+1.0
                    || hit.z<clicked.getZ() || hit.z>clicked.getZ()+1.0) throw new IllegalArgumentException("Invalid hit");
        }
    }
    public record Confirmation(UUID id, UUID offer, UUID domain, UUID session, long baseRevision, String planHash) {
        public Confirmation {
            Objects.requireNonNull(id); Objects.requireNonNull(offer); Objects.requireNonNull(domain); Objects.requireNonNull(session);
            revision(baseRevision); hash(planHash);
        }
        /** The opaque offer is part of request identity, including rejected and post-restart retries. */
        public String bindingHash() {
            try {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                digest.update(ByteBuffer.allocate(16).putLong(offer.getMostSignificantBits()).putLong(offer.getLeastSignificantBits()).array());
                return HexFormat.of().formatHex(digest.digest(HexFormat.of().parseHex(planHash)));
            } catch (java.security.NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
        }
    }
    public record Offer(UUID token, UUID domain, UUID session, long baseRevision, String planHash,
                        String dimension, BlockPos target, String product, int axis, boolean creative) {
        public Offer {
            Objects.requireNonNull(token); Objects.requireNonNull(domain); Objects.requireNonNull(session);
            revision(baseRevision); hash(planHash); resource(dimension); resource(product); target=position(target); ConstructionProtocol.axis(axis);
        }
        public Confirmation confirm(UUID id) { return new Confirmation(id,token,domain,session,baseRevision,planHash); }
    }
    public record OfferReply(UUID query, Status status, Offer offer) {
        public OfferReply {
            Objects.requireNonNull(query); Objects.requireNonNull(status);
            if (status.ordinal()>=Status.COMMITTED.ordinal() || (status==Status.READY)!=(offer!=null)) throw new IllegalArgumentException("Invalid offer response");
        }
    }
    public record Outcome(Confirmation request, Status status, long revision, List<UUID> created) {
        public Outcome {
            Objects.requireNonNull(request); Objects.requireNonNull(status); created=List.copyOf(created);
            if (revision!=(status==Status.COMMITTED?request.baseRevision()+1:request.baseRevision()) || status==Status.READY || created.size()>256 || new HashSet<>(created).size()!=created.size()
                    || (status==Status.COMMITTED ? created.isEmpty() : !created.isEmpty()))
                throw new IllegalArgumentException("Invalid construction outcome");
        }
    }

    public static void encodePreview(Preview value,FriendlyByteBuf b) {
        b.writeUUID(value.query()); b.writeUtf(value.dimension(),128); b.writeBlockPos(value.clicked()); b.writeByte(value.face().ordinal());
        b.writeDouble(value.hit().x); b.writeDouble(value.hit().y); b.writeDouble(value.hit().z);
        b.writeByte(value.hand().ordinal()); b.writeUtf(value.product(),128); b.writeByte(value.axis());
    }
    public static Preview decodePreview(FriendlyByteBuf b) {
        cap(b,1024);
        Preview value=new Preview(b.readUUID(),b.readUtf(128),b.readBlockPos(),enumeration(b,Direction.values()),
                new Vec3(b.readDouble(),b.readDouble(),b.readDouble()),enumeration(b,InteractionHand.values()),b.readUtf(128),b.readUnsignedByte());
        end(b); return value;
    }
    public static void encodeConfirmation(Confirmation value,FriendlyByteBuf b) {
        b.writeUUID(value.id()); b.writeUUID(value.offer()); b.writeUUID(value.domain()); b.writeUUID(value.session());
        b.writeLong(value.baseRevision()); b.writeUtf(value.planHash(),64);
    }
    public static Confirmation decodeConfirmation(FriendlyByteBuf b) {
        cap(b,256); Confirmation value=readConfirmation(b); end(b); return value;
    }
    private static Confirmation readConfirmation(FriendlyByteBuf b) {
        return new Confirmation(b.readUUID(),b.readUUID(),b.readUUID(),b.readUUID(),b.readLong(),b.readUtf(64));
    }
    public static void encodeOffer(OfferReply value,FriendlyByteBuf b) {
        b.writeUUID(value.query()); b.writeByte(value.status().ordinal()); Offer offer=value.offer();
        if (offer!=null) {
            b.writeUUID(offer.token()); b.writeUUID(offer.domain()); b.writeUUID(offer.session()); b.writeLong(offer.baseRevision());
            b.writeUtf(offer.planHash(),64); b.writeUtf(offer.dimension(),128); b.writeBlockPos(offer.target());
            b.writeUtf(offer.product(),128); b.writeByte(offer.axis()); b.writeBoolean(offer.creative());
        }
    }
    public static OfferReply decodeOffer(FriendlyByteBuf b) {
        cap(b,1024); UUID query=b.readUUID(); Status status=enumeration(b,Status.values()); Offer offer=null;
        if (status==Status.READY) offer=new Offer(b.readUUID(),b.readUUID(),b.readUUID(),b.readLong(),b.readUtf(64),b.readUtf(128),b.readBlockPos(),b.readUtf(128),b.readUnsignedByte(),bool(b));
        end(b); return new OfferReply(query,status,offer);
    }
    public static void encodeOutcome(Outcome value,FriendlyByteBuf b) {
        encodeConfirmation(value.request(),b); b.writeByte(value.status().ordinal()); b.writeLong(value.revision());
        b.writeVarInt(value.created().size()); for (UUID id:value.created()) b.writeUUID(id);
    }
    public static Outcome decodeOutcome(FriendlyByteBuf b) {
        cap(b,8192); Confirmation request=readConfirmation(b); Status status=enumeration(b,Status.values()); long revision=b.readLong();
        int count=b.readVarInt(); if(count<0 || count>256 || count*16!=b.readableBytes()) throw new DecoderException("Invalid piece count");
        List<UUID> created=new ArrayList<>(count); for(int i=0;i<count;i++)created.add(b.readUUID());
        end(b); return new Outcome(request,status,revision,created);
    }
    private static boolean bool(FriendlyByteBuf b) { int value=b.readUnsignedByte(); if(value>1)throw new DecoderException("Invalid boolean");return value==1; }
    private static <T>T enumeration(FriendlyByteBuf b,T[] values) { int index=b.readUnsignedByte();if(index>=values.length)throw new DecoderException("Unknown construction enum");return values[index]; }
    private static void cap(FriendlyByteBuf b,int bytes) { if(b.readableBytes()>bytes)throw new DecoderException("Construction packet capacity"); }
    private static void end(FriendlyByteBuf b) { if(b.isReadable())throw new DecoderException("Trailing construction data"); }
    private static void resource(String text) { if(text==null || text.length()>128 || !text.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw new IllegalArgumentException("Invalid resource"); }
    private static void hash(String text) { if(text==null || !text.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid plan hash"); }
    private static void revision(long revision) { if(revision<0 || revision==Long.MAX_VALUE)throw new IllegalArgumentException("Invalid revision"); }
    private static void axis(int value) { if(value<0 || value>2)throw new IllegalArgumentException("Invalid axis"); }
    private static BlockPos position(BlockPos p) {
        Objects.requireNonNull(p);
        if(p.getX()< -30000000 || p.getX()>=30000000 || p.getZ()< -30000000 || p.getZ()>=30000000 || p.getY()< -2048 || p.getY()>=2048)
            throw new IllegalArgumentException("Invalid position");
        return p.immutable();
    }
}
