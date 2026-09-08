package com.blockreality.impl.net;

import com.blockreality.api.AnalysisResult;
import com.blockreality.api.MemberSnapshot;
import com.blockreality.api.ShellSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.util.*;
import java.util.function.ToIntFunction;

/** Whole-element selection and owned payloads. No mechanics or field format lives here. */
final class DisplayDelivery {
    static final int MAX_PACKET_BYTES = 262_144;
    static final int HEADER_RESERVE = 2_048;
    static final int MAX_BLOCKS = 16_384;
    static final int MAX_STATIONS = 2_048;
    static final int MAX_MEMBERS = 64, MAX_SHELLS = 512;
    static final Limits LIMITS = new Limits(MAX_PACKET_BYTES - HEADER_RESERVE, MAX_BLOCKS, MAX_STATIONS);
    record Limits(int bytes, int blocks, int stations) {
        Limits { if (bytes < 0 || blocks < 0 || stations < 0) throw new IllegalArgumentException("negative display budget"); }
    }
    /** Shared by both decoders; charge declared counts before allocating their lists. */
    static final class ReadBudget {
        private int blocks = MAX_BLOCKS, stations = MAX_STATIONS;
        void blocks(int n) { if(n < 0 || n > blocks) throw new IllegalArgumentException("display block budget exceeded"); blocks -= n; }
        void stations(int n) { if(n < 0 || n > stations) throw new IllegalArgumentException("display station budget exceeded"); stations -= n; }
    }
    private record Encoded<T>(int index, T value, byte[] bytes) { }
    @FunctionalInterface private interface Writer<T> { void write(FriendlyByteBuf b, T item, boolean withheld); }
    private final List<Encoded<MemberSnapshot>> beams = new ArrayList<>();
    private final List<Encoded<ShellSnapshot>> facets = new ArrayList<>();
    private int bytesLeft, blocksLeft, stationsLeft;
    private final int initialBytes;
    private int scratchPeak;

    private DisplayDelivery(Limits limits) {
        bytesLeft = initialBytes = limits.bytes(); blocksLeft = limits.blocks(); stationsLeft = limits.stations();
    }
    static DisplayDelivery prepare(AnalysisResult r, Set<Integer> withheldMembers, Set<Integer> withheldShells) {
        return prepare(r, withheldMembers, withheldShells, LIMITS);
    }
    static DisplayDelivery prepare(AnalysisResult r, Set<Integer> wm, Set<Integer> ws, Limits limits) {
        var out = new DisplayDelivery(limits);
        int mi = "member".equals(r.governingKind()) ? find(r.members(), r.governing(), MemberSnapshot::id) : -1;
        int si = "shell".equals(r.governingKind()) ? find(r.shells(), r.governing(), ShellSnapshot::id) : -1;
        // Reserve the controlling element before either family spends the shared budget.
        if (mi >= 0) out.member(r.members().get(mi), mi, wm);
        if (si >= 0) out.shell(r.shells().get(si), si, ws);
        int tried = mi >= 0 ? 1 : 0;
        for(int i=0; i<r.members().size() && tried<MAX_MEMBERS; i++) {
            if(i == mi) continue;
            tried++; out.member(r.members().get(i), i, wm);
        }
        tried = si >= 0 ? 1 : 0;
        for(int i=0; i<r.shells().size() && tried<MAX_SHELLS; i++) {
            if(i == si) continue;
            tried++; out.shell(r.shells().get(i), i, ws);
        }
        out.beams.sort(Comparator.comparingInt(Encoded::index));
        out.facets.sort(Comparator.comparingInt(Encoded::index));
        return out;
    }
    private static <T> int find(List<T> all, int id, ToIntFunction<T> getId) {
        for(int i=0;i<all.size();i++) if(getId.applyAsInt(all.get(i)) == id) return i;
        return -1;
    }
    private void member(MemberSnapshot m, int index, Set<Integer> withheld) {
        add(beams,index,m,m.blocks().size(),m.stations().size(),withheld.contains(m.id()),MemberPacketCodec::write);
    }
    private void shell(ShellSnapshot s, int index, Set<Integer> withheld) {
        add(facets,index,s,s.blocks().size(),0,withheld.contains(s.id()),ShellPacketCodec::write);
    }
    private <T> void add(List<Encoded<T>> kept, int index, T item, int blocks, int stations, boolean withheld, Writer<T> writer) {
        if(blocks > blocksLeft || stations > stationsLeft || bytesLeft == 0) return;
        var scratch = new FriendlyByteBuf(Unpooled.buffer(Math.min(512, bytesLeft), bytesLeft));
        try {
            try { writer.write(scratch,item,withheld); }
            catch(IndexOutOfBoundsException tooLarge) { return; }
            int n = scratch.writerIndex();
            byte[] payload = new byte[n]; scratch.getBytes(0,payload);
            kept.add(new Encoded<>(index,item,payload));
            bytesLeft -= n; blocksLeft -= blocks; stationsLeft -= stations;
        } finally {
            scratchPeak = Math.max(scratchPeak,scratch.capacity());
            scratch.release();
        }
    }
    List<MemberSnapshot> members() { return beams.stream().map(Encoded::value).toList(); }
    List<ShellSnapshot> shells() { return facets.stream().map(Encoded::value).toList(); }
    void writeMembers(FriendlyByteBuf b) { for(var e:beams) b.writeBytes(e.bytes()); }
    void writeShells(FriendlyByteBuf b) { for(var e:facets) b.writeBytes(e.bytes()); }
    int payloadBytes() { return initialBytes - bytesLeft; }
    int scratchPeak() { return scratchPeak; }
}
