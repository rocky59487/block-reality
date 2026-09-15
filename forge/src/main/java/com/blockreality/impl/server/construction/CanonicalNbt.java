package com.blockreality.impl.server.construction;

import net.minecraft.nbt.*;
import java.io.*;
import java.util.*;

/** Bounded vanilla NBT with deterministic compound ordering, for participant comparisons. */
final class CanonicalNbt {
    static final int MAX_DEPTH = 64, MAX_TAGS = 262144, MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    private CanonicalNbt() { }

    static byte[] encode(CompoundTag root, int maximum) throws IOException {
        var bytes = new LimitedOutput(maximum);
        var out = new DataOutputStream(bytes);
        out.writeByte(Tag.TAG_COMPOUND); out.writeUTF("");
        write(root, out, new Budget(), 0);
        return bytes.bytes();
    }

    /** Disk NBT may have arbitrary key order. Duplicate keys and data loss are never accepted. */
    static CompoundTag read(byte[] bytes, int maximum) throws IOException {
        limit(maximum);
        if (bytes.length > maximum) throw new IOException("NBT byte capacity exceeded");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readUnsignedByte() != Tag.TAG_COMPOUND || !in.readUTF().isEmpty())
            throw new IOException("Expected unnamed compound NBT root");
        CompoundTag result = (CompoundTag) read(Tag.TAG_COMPOUND, in, new Budget(), 0);
        if (in.available() != 0) throw new IOException("Trailing NBT data");
        return result;
    }

    static CompoundTag decode(byte[] bytes, int maximum) throws IOException {
        CompoundTag root = read(bytes, maximum);
        if (!Arrays.equals(bytes, encode(root, maximum))) throw new IOException("Noncanonical NBT image");
        return root;
    }

    private static void write(Tag tag, DataOutputStream out, Budget budget, int depth) throws IOException {
        int type = tag.getId(); budget.visit(type, depth);
        switch (type) {
            case Tag.TAG_BYTE -> out.writeByte(((ByteTag) tag).getAsByte());
            case Tag.TAG_SHORT -> out.writeShort(((ShortTag) tag).getAsShort());
            case Tag.TAG_INT -> out.writeInt(((IntTag) tag).getAsInt());
            case Tag.TAG_LONG -> out.writeLong(((LongTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> out.writeInt(Float.floatToRawIntBits(((FloatTag) tag).getAsFloat()));
            case Tag.TAG_DOUBLE -> out.writeLong(Double.doubleToRawLongBits(((DoubleTag) tag).getAsDouble()));
            case Tag.TAG_BYTE_ARRAY -> {
                byte[] array = ((ByteArrayTag) tag).getAsByteArray(); out.writeInt(array.length); out.write(array);
            }
            case Tag.TAG_STRING -> out.writeUTF(tag.getAsString());
            case Tag.TAG_LIST -> {
                ListTag list = (ListTag) tag; budget.children(list.size());
                out.writeByte(list.isEmpty() ? Tag.TAG_END : list.getElementType()); out.writeInt(list.size());
                for (Tag child : list) write(child, out, budget, depth + 1);
            }
            case Tag.TAG_COMPOUND -> {
                CompoundTag compound = (CompoundTag) tag; budget.children(compound.size());
                var keys = new ArrayList<>(compound.getAllKeys()); Collections.sort(keys);
                for (String key : keys) {
                    Tag child = compound.get(key);
                    if (child == null || child.getId() == Tag.TAG_END) throw new IOException("Invalid compound child");
                    out.writeByte(child.getId()); out.writeUTF(key); write(child, out, budget, depth + 1);
                }
                out.writeByte(Tag.TAG_END);
            }
            case Tag.TAG_INT_ARRAY -> {
                int[] array = ((IntArrayTag) tag).getAsIntArray(); out.writeInt(array.length);
                for (int value : array) out.writeInt(value);
            }
            case Tag.TAG_LONG_ARRAY -> {
                long[] array = ((LongArrayTag) tag).getAsLongArray(); out.writeInt(array.length);
                for (long value : array) out.writeLong(value);
            }
            default -> throw new IOException("Unknown NBT tag type");
        }
    }

    private static Tag read(int type, DataInputStream in, Budget budget, int depth) throws IOException {
        budget.visit(type, depth);
        return switch (type) {
            case Tag.TAG_BYTE -> ByteTag.valueOf(in.readByte());
            case Tag.TAG_SHORT -> ShortTag.valueOf(in.readShort());
            case Tag.TAG_INT -> IntTag.valueOf(in.readInt());
            case Tag.TAG_LONG -> LongTag.valueOf(in.readLong());
            case Tag.TAG_FLOAT -> {
                int bits = in.readInt();
                if (bits == 0x80000000) throw new IOException("NBT float negative zero is not representable");
                float value = Float.intBitsToFloat(bits);
                if (Float.floatToRawIntBits(value) != bits) throw new IOException("NBT float bits are not representable");
                yield FloatTag.valueOf(value);
            }
            case Tag.TAG_DOUBLE -> {
                long bits = in.readLong();
                if (bits == 0x8000000000000000L) throw new IOException("NBT double negative zero is not representable");
                double value = Double.longBitsToDouble(bits);
                if (Double.doubleToRawLongBits(value) != bits) throw new IOException("NBT double bits are not representable");
                yield DoubleTag.valueOf(value);
            }
            case Tag.TAG_BYTE_ARRAY -> {
                int count = length(in, 1); byte[] array = new byte[count]; in.readFully(array); yield new ByteArrayTag(array);
            }
            case Tag.TAG_STRING -> StringTag.valueOf(in.readUTF());
            case Tag.TAG_LIST -> {
                int childType = in.readUnsignedByte();
                if (childType > Tag.TAG_LONG_ARRAY) throw new IOException("Unknown list element type");
                int count = length(in, minimum(childType)); budget.children(count);
                if (childType == Tag.TAG_END && count != 0) throw new IOException("Nonempty end-tag list");
                ListTag list = new ListTag();
                for (int i = 0; i < count; i++) list.add(read(childType, in, budget, depth + 1));
                yield list;
            }
            case Tag.TAG_COMPOUND -> {
                CompoundTag compound = new CompoundTag();
                for (int childType; (childType = in.readUnsignedByte()) != Tag.TAG_END;) {
                    if (childType > Tag.TAG_LONG_ARRAY) throw new IOException("Unknown compound element type");
                    String key = in.readUTF();
                    if (compound.contains(key)) throw new IOException("Duplicate NBT compound key");
                    compound.put(key, read(childType, in, budget, depth + 1));
                }
                yield compound;
            }
            case Tag.TAG_INT_ARRAY -> {
                int count = length(in, 4); int[] array = new int[count];
                for (int i = 0; i < count; i++) array[i] = in.readInt(); yield new IntArrayTag(array);
            }
            case Tag.TAG_LONG_ARRAY -> {
                int count = length(in, 8); long[] array = new long[count];
                for (int i = 0; i < count; i++) array[i] = in.readLong(); yield new LongArrayTag(array);
            }
            default -> throw new IOException("Unknown NBT tag type");
        };
    }

    private static int length(DataInputStream in, int width) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > in.available() / width) throw new IOException("Impossible NBT collection length");
        return count;
    }
    private static int minimum(int type) {
        return switch (type) { case 2, 8 -> 2; case 3, 5, 7, 11, 12 -> 4; case 4, 6 -> 8; case 9 -> 5; default -> 1; };
    }
    private static void limit(int maximum) {
        if (maximum < 4 || maximum > MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("Invalid NBT byte bound");
    }
    private static final class Budget {
        int remaining = MAX_TAGS;
        void visit(int type, int depth) throws IOException {
            if (--remaining < 0) throw new IOException("NBT tag capacity exceeded");
            if ((type == Tag.TAG_COMPOUND || type == Tag.TAG_LIST) && depth >= MAX_DEPTH)
                throw new IOException("NBT nesting capacity exceeded");
        }
        void children(int count) throws IOException {
            if (count > remaining) throw new IOException("NBT tag capacity exceeded");
        }
    }
    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final int maximum;
        LimitedOutput(int maximum) { limit(maximum); this.maximum = maximum; }
        private void reserve(int count) throws IOException {
            if (count > maximum - out.size()) throw new IOException("NBT byte capacity exceeded");
        }
        @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            Objects.checkFromIndexSize(offset, count, bytes.length); reserve(count); out.write(bytes, offset, count);
        }
        byte[] bytes() { return out.toByteArray(); }
    }
}
