package com.blockreality.impl.net;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeOnlyPacketGoldenTest {
    @Test void fixedPacketsRemainByteIdenticalAcrossLegacyRemoval() throws Exception {
        var hashes = new ArrayList<String>();
        for (boolean verdict : new boolean[]{false, true}) {
            for (boolean display : new boolean[]{false, true}) {
                hashes.add(hash(MemberPacketCodecTest.bytes(MemberPacketCodecTest.member(verdict, display))));
                for (boolean raw : new boolean[]{false, true}) {
                    hashes.add(hash(ShellPacketCodecTest.bytes(ShellPacketCodecTest.shell(verdict, display, raw))));
                }
            }
        }
        assertEquals(List.of(
                "7fec71532b6ebae06e3eaaef7579c88582297828fc6691d448649a26fb30241e",
                "7067a703995aacb07c2a6476be78c17edf5c57aa7ca17471816b6f57a872b2e6",
                "8ec2cb2977040be02875990e35f83944ebe958c0d188a64cd1f8b29b2ed98864",
                "55c4ba36772b13d58d2708400f40af893f2b2fd06300741fe0e262388d3e52f9",
                "4ddd7a03c1890cae71cccfa5719b1ab5debbb7678132dce3c7ccfc5866d3ce2d",
                "1ac66b341a73cd2585cda6b87f377bf6576bcb85b4d5eda102f8d56624520e77",
                "d9d42af2a17424912bb256a62684fdf897073f547ed2993fd01f6aa104734036",
                "e0812398fa2498bfb5f8d58e573ad6bd54623b0ac01587ffd09deec08193ead6",
                "c358534acb0e7c3a25baa76e579b7ca2ed7aef92a985537598ccc26f369d777e",
                "76898a43a4735d56fcdb225d3a223eb86979bdcc515fdc8167c2fb75d09e747a",
                "c81db1ce6d44a126a9833119cbc6b826b79e57b8dae8e34ae56e7541c751e1bd",
                "fd9373c49c1c8464050f670d964cda3ef4774ee1595e7ab9b973e6f78e6137d2"
        ), hashes, "channel 11 payload bytes changed during legacy removal");
    }

    private static String hash(FriendlyByteBuf packet) throws Exception {
        try {
            byte[] bytes = new byte[packet.readableBytes()]; packet.getBytes(packet.readerIndex(), bytes);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } finally { packet.release(); }
    }
}
