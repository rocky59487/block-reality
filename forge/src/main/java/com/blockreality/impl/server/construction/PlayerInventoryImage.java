package com.blockreality.impl.server.construction;

import com.blockreality.core.transaction.ConstructionTransaction.Value;
import net.minecraft.nbt.*;
import java.io.IOException;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.MAX_VALUE_BYTES;

/** Exact saved inventory slots; no item construction, refunds, game rules or player loading. */
final class PlayerInventoryImage {
    private PlayerInventoryImage() { }

    static Value read(CompoundTag player, int slot) throws IOException {
        savedSlot(slot);
        CompoundTag item = entries(player).get(slot);
        if (item == null) return Value.missing();
        CompoundTag image = item.copy(); image.remove("Slot");
        return Value.of(CanonicalNbt.encode(image, MAX_VALUE_BYTES));
    }

    /** Returns an independent whole-player document. Untouched entry order and fields are retained. */
    static CompoundTag replace(CompoundTag player, Map<Integer, Value> changes) throws IOException {
        entries(player); // Validate every slot, including ones not being changed.
        var replacements = new TreeMap<Integer, CompoundTag>();
        for (var change : changes.entrySet()) {
            int slot = change.getKey(); int encoded = savedSlot(slot);
            Value value = Objects.requireNonNull(change.getValue());
            CompoundTag item = null;
            if (value.present()) {
                item = CanonicalNbt.decode(value.bytes(), MAX_VALUE_BYTES);
                if (item.contains("Slot")) throw new IOException("Slot belongs to the resource key, not the item image");
                validateItem(item); item.putByte("Slot", (byte) encoded);
            }
            replacements.put(slot, item);
        }
        CompoundTag result = player.copy(); ListTag inventory = new ListTag();
        for (Tag tag : player.getList("Inventory", Tag.TAG_COMPOUND)) {
            CompoundTag old = (CompoundTag) tag; int slot = slot(old);
            if (!replacements.containsKey(slot)) inventory.add(old.copy());
            else {
                CompoundTag item = replacements.remove(slot);
                if (item != null) inventory.add(item);
            }
        }
        for (CompoundTag item : replacements.values()) if (item != null) inventory.add(item);
        result.put("Inventory", inventory); return result;
    }

    private static Map<Integer, CompoundTag> entries(CompoundTag player) throws IOException {
        if (!(player.get("Inventory") instanceof ListTag inventory)
                || (!inventory.isEmpty() && inventory.getElementType() != Tag.TAG_COMPOUND)
                || inventory.size() > 41) throw new IOException("Invalid player inventory list");
        var slots = new HashMap<Integer, CompoundTag>();
        for (Tag tag : inventory) {
            CompoundTag item = (CompoundTag) tag; validateItem(item);
            if (slots.put(slot(item), item) != null) throw new IOException("Duplicate player inventory slot");
        }
        return slots;
    }
    private static void validateItem(CompoundTag item) throws IOException {
        if (!item.contains("id", Tag.TAG_STRING) || item.getString("id").isEmpty()
                || !item.contains("Count", Tag.TAG_BYTE) || item.getByte("Count") <= 0)
            throw new IOException("Invalid saved item image");
    }
    private static int slot(CompoundTag item) throws IOException {
        if (!item.contains("Slot", Tag.TAG_BYTE)) throw new IOException("Missing or mistyped inventory slot");
        int encoded = Byte.toUnsignedInt(item.getByte("Slot"));
        if (encoded <= 35) return encoded;
        if (encoded >= 100 && encoded <= 103) return encoded - 64;
        if (encoded == 150) return 40;
        throw new IOException("Unknown saved inventory slot");
    }
    private static int savedSlot(int slot) {
        if (slot < 0 || slot > 40) throw new IllegalArgumentException("Invalid inventory resource slot");
        return slot <= 35 ? slot : slot <= 39 ? slot + 64 : 150;
    }
}
