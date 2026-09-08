package com.blockreality.impl.net;

import com.blockreality.api.*;
import com.blockreality.api.geom.BlockKey;
import com.blockreality.api.geom.Vec3d;
import com.blockreality.core.render.ShellMesh;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ShellPacketCodecTest {
    private static ShellSnapshot shell(boolean overloaded, boolean display, boolean raw) {
        List<BlockKey> blocks = new ArrayList<>();
        for (int i=0;i<6;i++) blocks.add(new BlockKey(i,64,-7));
        List<ShellDisplayField.Surface> top = new ArrayList<>(), bottom = new ArrayList<>();
        for (int k=0;k<4;k++) {
            top.add(new ShellDisplayField.Surface(2+k,-1,.1*k,10+k));
            bottom.add(new ShellDisplayField.Surface(1,-20-k,-.1*k,30+k));
        }
        // Large world coordinates would collapse adjacent corners if narrowed to float.
        double x = 29_999_000_500.;
        var f = new ShellDisplayField(List.of(new Vec3d(x,64500,500),new Vec3d(x+1000,64500,500),
                new Vec3d(x+1000,64500,1500),new Vec3d(x,64500,1500)),
                new Vec3d(1,0,0),new Vec3d(0,0,1),new Vec3d(0,-1,0),top,bottom);
        return new ShellSnapshot(4,"slab","slab",200,1,raw ? .999 : Double.NaN,true,false,blocks,
                Optional.empty(),display ? Optional.of(f) : Optional.empty(),overloaded,6);
    }
    private static StressResultPacket packet(ShellSnapshot s) {
        var result = new AnalysisResult(new WorldRevision(19),true,false,"",1,4,"shell",1,0,0,0,
                BucklingState.DISABLED_BY_REQUEST,List.of(),List.of(s),List.of());
        return StressResultPacket.of(result,"minecraft:overworld",false);
    }
    private static FriendlyByteBuf bytes(ShellSnapshot s) {
        var b = new FriendlyByteBuf(Unpooled.buffer()); StressResultPacket.encode(packet(s),b); return b;
    }

    @Test void completePacketPreservesVerdictBlocksAndSamplesThroughPicking() {
        for (boolean verdict : new boolean[]{false,true}) {
            var source = shell(verdict,true,false); var buf = bytes(source);
            try {
                var out = StressResultPacket.decode(buf); assertTrue(out.valid(),out.invalidReason());
                var s = out.shells().get(0); assertEquals(1,s.dc()); assertEquals(verdict,s.overloaded());
                assertEquals(source.blocks(),s.blocks()); assertTrue(s.governingTopFace()); assertEquals(6,s.governingFibre());
                assertTrue(s.rawDc().isEmpty()); assertTrue(s.field().isEmpty());
                var f = s.display().orElseThrow(); var original = source.display().orElseThrow();
                assertEquals(original.cornersMm(),f.cornersMm()); assertEquals(original.normal(),f.normal());
                assertEquals(original.top(),f.top()); assertEquals(original.bottom(),f.bottom());
                var hit = ShellMesh.locate(out.shells(),f.centreMm()).orElseThrow();
                assertEquals(3.5,hit.field().signedPrincipal(hit.xi(),hit.eta(),1));
                assertEquals(-21.5,hit.field().signedPrincipal(hit.xi(),hit.eta(),-1));
                assertEquals(23,ShellMesh.peakMpa(out.shells()));
            } finally { buf.release(); }
        }
    }
    @Test void missingDisplayAndOptionalRawRemainDistinct() {
        for (boolean raw : new boolean[]{false,true}) {
            var buf = bytes(shell(true,false,raw));
            try {
                var out = StressResultPacket.decode(buf); assertTrue(out.valid(),out.invalidReason());
                var s = out.shells().get(0); assertTrue(s.display().isEmpty());
                assertEquals(raw,s.rawDc().isPresent()); if (raw) assertEquals(.999,s.rawDc().orElseThrow());
                assertTrue(ShellMesh.locate(out.shells(),Vec3d.ZERO).isEmpty());
            } finally { buf.release(); }
        }
    }
    @Test void everyTruncationOfASampledShellPacketIsRejected() {
        var full = bytes(shell(true,true,true));
        try {
            for (int n=0; n<full.readableBytes(); n++) {
                var b = new FriendlyByteBuf(full.copy(0,n));
                try { assertFalse(StressResultPacket.decode(b).valid(),"truncated at "+n); }
                finally { b.release(); }
            }
        } finally { full.release(); }
    }
    @Test void nonFiniteSurfaceAndInvalidFrameRejectTheWholePacket() {
        for (double poison : new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            var b = bytes(shell(true,true,false));
            try {
                b.setDouble(b.writerIndex()-8,poison);
                assertFalse(StressResultPacket.decode(b).valid());
            } finally { b.release(); }
        }
        var b = bytes(shell(true,true,false));
        try {
            // Last 256 B are eight 4-double samples; immediately before them is the normal.
            for (int j=0; j<3; j++) b.setDouble(b.writerIndex()-256-24+8*j,0);
            assertFalse(StressResultPacket.decode(b).valid());
        } finally { b.release(); }
    }
}
