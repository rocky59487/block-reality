package com.blockreality.impl.server.construction;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalNbtTest {
    static final int LIMIT = 1024 * 1024;
    @FunctionalInterface interface Payload { void write(DataOutputStream out) throws IOException; }
    static byte[] raw(int type, Payload payload) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeByte(10); out.writeUTF(""); out.writeByte(type); out.writeUTF("a");
        payload.write(out); out.writeByte(0); return bytes.toByteArray();
    }

    @Test void everyTagTypeRoundTripsWithVanillaAndIndependentImages() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putByte("byte", (byte) -128); root.putShort("short", (short) -32768);
        root.putInt("int", Integer.MIN_VALUE); root.putLong("long", Long.MAX_VALUE);
        root.putFloat("float", Float.MIN_VALUE); root.putDouble("double", Double.NEGATIVE_INFINITY);
        root.putByteArray("bytes", new byte[]{-1, 0, 127}); root.putString("string", "鋼骨\u0000\ud83e\uddf1");
        ListTag list = new ListTag(); list.add(StringTag.valueOf("second")); list.add(StringTag.valueOf("first"));
        root.put("list", list); root.put("empty", new ListTag());
        CompoundTag nested = new CompoundTag(); nested.putString("key", "value"); root.put("compound", nested);
        root.putIntArray("ints", new int[]{Integer.MAX_VALUE, -1}); root.putLongArray("longs", new long[]{Long.MIN_VALUE, 1});
        byte[] image = CanonicalNbt.encode(root, LIMIT);
        CompoundTag decoded = CanonicalNbt.decode(image, LIMIT);
        assertEquals(root, decoded); assertArrayEquals(image, CanonicalNbt.encode(decoded, LIMIT));
        assertEquals(root, NbtIo.read(new DataInputStream(new ByteArrayInputStream(image))));
        var vanilla = new ByteArrayOutputStream(); NbtIo.write(root, new DataOutputStream(vanilla));
        assertArrayEquals(image, CanonicalNbt.encode(CanonicalNbt.read(vanilla.toByteArray(), LIMIT), LIMIT));
        decoded.getCompound("compound").putString("key", "changed");
        assertEquals("value", root.getCompound("compound").getString("key"));
        assertEquals("value", CanonicalNbt.decode(image, LIMIT).getCompound("compound").getString("key"));
    }

    @Test void compoundInsertionOrderDoesNotChangeImageAndSmallGoldenIsExact() throws Exception {
        CompoundTag first = new CompoundTag(), second = new CompoundTag();
        first.putInt("Aa", 1); first.putInt("BB", 2); second.putInt("BB", 2); second.putInt("Aa", 1);
        assertArrayEquals(CanonicalNbt.encode(first, LIMIT), CanonicalNbt.encode(second, LIMIT));
        CompoundTag simple = new CompoundTag(); simple.putInt("a", 7);
        assertEquals("0a0000030001610000000700", HexFormat.of().formatHex(CanonicalNbt.encode(simple, LIMIT)));
        byte[] unsorted = raw(3, out -> { out.writeInt(7); out.writeByte(3); out.writeUTF("A"); out.writeInt(8); });
        assertEquals(8, CanonicalNbt.read(unsorted, LIMIT).getInt("A"));
        assertThrows(IOException.class, () -> CanonicalNbt.decode(unsorted, LIMIT));
    }

    @Test void numericPayloadBitsSurviveAndUnrepresentableNegativeZeroRefuses() throws Exception {
        for (int bits : new int[]{0, 1, 0x80000001, 0x7f800000, 0xff800000, 0x7fc12345, 0xffc00123}) {
            byte[] image = raw(5, out -> out.writeInt(bits));
            assertEquals(bits, Float.floatToRawIntBits(CanonicalNbt.decode(image, LIMIT).getFloat("a")));
        }
        for (long bits : new long[]{0, 1, Long.MIN_VALUE + 1, 0x7ff8000000000123L, 0xfff0000000000000L}) {
            byte[] image = raw(6, out -> out.writeLong(bits));
            assertEquals(bits, Double.doubleToRawLongBits(CanonicalNbt.decode(image, LIMIT).getDouble("a")));
        }
        assertThrows(IOException.class, () -> CanonicalNbt.read(raw(5, out -> out.writeInt(Integer.MIN_VALUE)), LIMIT));
        assertThrows(IOException.class, () -> CanonicalNbt.read(raw(6, out -> out.writeLong(Long.MIN_VALUE)), LIMIT));
    }

    @Test void truncationTrailingDuplicateUnknownAndImpossibleLengthsRefuse() throws Exception {
        CompoundTag root = new CompoundTag(); root.putString("a", "material"); root.putLongArray("b", new long[]{1, 2, 3});
        byte[] image = CanonicalNbt.encode(root, LIMIT);
        for (int length = 0; length < image.length; length++) {
            byte[] truncated = Arrays.copyOf(image, length);
            assertThrows(IOException.class, () -> CanonicalNbt.read(truncated, LIMIT), "length " + length);
        }
        assertThrows(IOException.class, () -> CanonicalNbt.read(Arrays.copyOf(image, image.length + 1), LIMIT));
        byte[] duplicate = raw(3, out -> { out.writeInt(1); out.writeByte(3); out.writeUTF("a"); out.writeInt(2); });
        assertThrows(IOException.class, () -> CanonicalNbt.read(duplicate, LIMIT));
        assertThrows(IOException.class, () -> CanonicalNbt.read(raw(13, out -> { }), LIMIT));
        for (int type : new int[]{7, 11, 12}) for (int count : new int[]{-1, Integer.MAX_VALUE})
            assertThrows(IOException.class, () -> CanonicalNbt.read(raw(type, out -> out.writeInt(count)), LIMIT));
        assertThrows(IOException.class, () -> CanonicalNbt.read(raw(9, out -> { out.writeByte(8); out.writeInt(Integer.MAX_VALUE); }), LIMIT));
        assertThrows(IOException.class, () -> CanonicalNbt.read(raw(9, out -> { out.writeByte(0); out.writeInt(1); }), LIMIT));
        assertThrows(IOException.class, () -> CanonicalNbt.read(raw(9, out -> { out.writeByte(13); out.writeInt(0); }), LIMIT));
        assertThrows(IOException.class, () -> CanonicalNbt.read(raw(8, out -> { out.writeShort(1); out.writeByte(0xff); }), LIMIT));
    }

    @Test void byteDepthAndTagBudgetsRefuseBeforeLargeAllocation() throws Exception {
        CompoundTag exact = new CompoundTag(); exact.putByteArray("a", new byte[LIMIT - 12]);
        assertEquals(LIMIT, CanonicalNbt.encode(exact, LIMIT).length);
        exact.putByteArray("a", new byte[LIMIT - 11]); assertThrows(IOException.class, () -> CanonicalNbt.encode(exact, LIMIT));
        assertThrows(IOException.class, () -> CanonicalNbt.read(new byte[LIMIT + 1], LIMIT));
        CompoundTag root = new CompoundTag(), tip = root;
        for (int i = 1; i < 64; i++) { CompoundTag child = new CompoundTag(); tip.put("a", child); tip = child; }
        assertNotNull(CanonicalNbt.decode(CanonicalNbt.encode(root, LIMIT), LIMIT));
        tip.put("a", new CompoundTag()); assertThrows(IOException.class, () -> CanonicalNbt.encode(root, LIMIT));
        byte[] nested = raw(10, out -> { for (int i = 0; i < 64; i++) { out.writeByte(10); out.writeUTF("a"); } for (int i = 0; i < 65; i++) out.writeByte(0); });
        assertThrows(IOException.class, () -> CanonicalNbt.read(nested, LIMIT));
        byte[] excessive = raw(9, out -> { out.writeByte(1); out.writeInt(CanonicalNbt.MAX_TAGS); out.write(new byte[CanonicalNbt.MAX_TAGS]); });
        assertThrows(IOException.class, () -> CanonicalNbt.read(excessive, LIMIT));
        ListTag list = new ListTag(); for (int i = 0; i < CanonicalNbt.MAX_TAGS; i++) list.add(ByteTag.ZERO);
        CompoundTag many = new CompoundTag(); many.put("a", list);
        assertThrows(IOException.class, () -> CanonicalNbt.encode(many, LIMIT));
    }
}
