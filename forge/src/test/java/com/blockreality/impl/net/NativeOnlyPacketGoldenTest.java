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
                "188bc189decc611c62f62393cf5b864a74ac33f731d94a10c95638ae960965c1",
                "3e59f8cb240fcef382aea4d3b0a325b2cbcf6bb3f4012f452a19acfae0f8bdeb",
                "d2e72890d8daa2e6098b1ec714d3a379280b5c45fa3c650c05a50694ff7a8c6c",
                "bd555bcff47af01626da2423ad769f960d3bed935722289ffff2274505545af7",
                "44536f274fc725b00c9e423f96021dd4d8d98414870a1ac8b2821fa41d6e6252",
                "d3f8d3cd38eb080d5587e3483aad916a7efc0dc03cd33f55ca31bd77af2a1920",
                "3fc19e2810e5fa7b29701f345e6e83acc65e3ef0875116359643f7b62775e4c9",
                "0d966ff343fb6c6ca8f9c4b0ce264ae88ab7166b9442c6e6779ca1481060269f",
                "45e1955b31b7cc66b9916885c6fd6897c637d3e5d9bf8765f2af569a3a138bb8",
                "6953bbffc75e0b6ca15e4cb5696190cf1f319c28dacd73e93096b7d2607d02d5",
                "4dad048f5acb7dfafc8bed2c76f66b74525614a73349d88629e2aff0f9fd7418",
                "948905c503992ad75fb243b40ceb831defd884bc07459e0a8e061e02870b9963"
        ), hashes, "channel 11 payload bytes changed during legacy removal");
    }

    private static String hash(FriendlyByteBuf packet) throws Exception {
        try {
            byte[] bytes = new byte[packet.readableBytes()]; packet.getBytes(packet.readerIndex(), bytes);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } finally { packet.release(); }
    }
}
