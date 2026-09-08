package com.blockreality.impl.net;

import com.blockreality.api.AnalysisResult;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.util.*;

/** Explicit measurement entry point, outside the ordinary JUnit suite. No timing pass line. */
public final class DisplayDeliveryProbe {
    private static final com.sun.management.ThreadMXBean MEMORY = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static long allocated() { return MEMORY.getThreadAllocatedBytes(Thread.currentThread().getId()); }
    public static void main(String[] args) throws Exception {
        MEMORY.setThreadAllocatedMemoryEnabled(true);
        System.out.println("ENV " + System.getProperty("java.version") + " " + System.getProperty("os.name") + " " + System.getenv("COMPUTERNAME") + " BLAS=" + System.getenv("OPENBLAS_NUM_THREADS"));
        for (String name : List.of("small","dense","oversize","shell-heavy")) {
            AnalysisResult input=DisplayDeliveryFixtures.fixture(name);
            for(int i=0;i<5;i++) run(input);
            String hash=null;
            for(int trial=0;trial<3;trial++) {
                long[] sum=new long[4]; Row row=null;
                for(int i=0;i<20;i++) {
                    row=run(input);for(int k=0;k<4;k++)sum[k]+=row.metrics[k];
                    if(hash==null)hash=row.hash;
                    if(!hash.equals(row.hash))throw new AssertionError("non-deterministic packet");
                }
                System.out.printf(Locale.ROOT,"DELIVERY {\"fixture\":\"%s\",\"trial\":%d,\"bytes\":%d,\"buffer_capacity\":%d,\"members\":%d,\"shells\":%d,\"blocks\":%d,\"stations\":%d,\"prepare_ns\":%d,\"encode_ns\":%d,\"decode_ns\":%d,\"allocated_bytes\":%d,\"sha256\":\"%s\"}%n",
                        name,trial,row.bytes,row.capacity,row.members,row.shells,row.blocks,row.stations,sum[0]/20,sum[1]/20,sum[2]/20,sum[3]/20,row.hash);
            }
        }
    }
    private record Row(long[] metrics,int bytes,int capacity,int members,int shells,int blocks,int stations,String hash) { }
    private static Row run(AnalysisResult input) throws Exception {
        long a=allocated(), t=System.nanoTime();
        var p=StressResultPacket.of(input,"minecraft:overworld",false);
        long ready=System.nanoTime();
        var b=new FriendlyByteBuf(Unpooled.buffer());
        try {
            StressResultPacket.encode(p,b);long encoded=System.nanoTime();int bytes=b.readableBytes(), capacity=b.capacity();
            var q=StressResultPacket.decode(b);long decoded=System.nanoTime(), used=allocated()-a;
            if(!q.valid())throw new AssertionError(q.invalidReason());
            byte[] raw=new byte[bytes];b.getBytes(0,raw);
            int blocks=q.members().stream().mapToInt(m->m.blocks().size()).sum()+q.shells().stream().mapToInt(s->s.blocks().size()).sum();
            int stations=q.members().stream().mapToInt(m->m.stations().size()).sum();
            return new Row(new long[]{ready-t,encoded-ready,decoded-encoded,used},bytes,capacity,q.members().size(),q.shells().size(),blocks,stations,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        } finally { b.release(); }
    }
}
