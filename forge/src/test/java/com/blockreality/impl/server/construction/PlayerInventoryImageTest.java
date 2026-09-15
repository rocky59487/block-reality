package com.blockreality.impl.server.construction;

import com.blockreality.core.transaction.ConstructionTransaction.Value;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerInventoryImageTest {
    static final UUID ACTOR = new UUID(11, 12);
    static CompoundTag item(int slot, int count) {
        CompoundTag item = new CompoundTag(); item.putByte("Slot", (byte) slot);
        item.putString("id", "blockreality:steel"); item.putByte("Count", (byte) count);
        CompoundTag tag = new CompoundTag(); tag.putString("display", "鋼骨"); tag.putIntArray("custom", new int[]{8, -1});
        item.put("tag", tag); CompoundTag caps = new CompoundTag(); caps.putLong("owner", 123456789L); item.put("ForgeCaps", caps);
        return item;
    }
    static CompoundTag player() {
        CompoundTag player = new CompoundTag(); player.putUUID("UUID", ACTOR); player.putInt("DataVersion", 3465);
        player.putString("Dimension", "minecraft:overworld"); player.putFloat("Health", 17.5f); player.putInt("XpTotal", 912);
        CompoundTag custom = new CompoundTag(); custom.putString("unrelated", "preserve"); player.put("ForgeData", custom);
        ListTag inventory = new ListTag(); inventory.add(item(0, 10)); inventory.add(item(150, 3));
        player.put("Inventory", inventory); return player;
    }

    @Test void mapsAllVanillaSlotsAndPreservesExactItemData() throws Exception {
        CompoundTag player = player(); ListTag inventory = new ListTag();
        for (int slot = 0; slot < 41; slot++) inventory.add(item(slot < 36 ? slot : slot < 40 ? slot + 64 : 150, slot + 1));
        player.put("Inventory", inventory);
        for (int slot = 0; slot < 41; slot++) {
            Value image = PlayerInventoryImage.read(player, slot);
            CompoundTag tag = CanonicalNbt.decode(image.bytes(), CanonicalNbtTest.LIMIT);
            assertEquals(slot + 1, tag.getByte("Count")); assertFalse(tag.contains("Slot"));
            assertEquals(123456789L, tag.getCompound("ForgeCaps").getLong("owner"));
        }
        assertThrows(IllegalArgumentException.class, () -> PlayerInventoryImage.read(player, -1));
        assertThrows(IllegalArgumentException.class, () -> PlayerInventoryImage.read(player, 41));
    }

    @Test void modifiesOnlyRequestedSlotsAndNeverAliasesInput() throws Exception {
        CompoundTag player = player(); byte[] before = CanonicalNbt.encode(player, CanonicalNbtTest.LIMIT);
        CompoundTag reduced = CanonicalNbt.decode(PlayerInventoryImage.read(player, 0).bytes(), CanonicalNbtTest.LIMIT);
        reduced.putByte("Count", (byte) 8); Value changed = Value.of(CanonicalNbt.encode(reduced, CanonicalNbtTest.LIMIT));
        CompoundTag result = PlayerInventoryImage.replace(player, Map.of(0, changed, 40, Value.missing(), 7, changed));
        assertEquals(changed, PlayerInventoryImage.read(result, 0)); assertEquals(changed, PlayerInventoryImage.read(result, 7));
        assertFalse(PlayerInventoryImage.read(result, 40).present()); assertFalse(PlayerInventoryImage.read(result, 8).present());
        assertEquals(912, result.getInt("XpTotal")); assertEquals(player.get("ForgeData"), result.get("ForgeData"));
        result.getCompound("ForgeData").putString("unrelated", "edit");
        assertArrayEquals(before, CanonicalNbt.encode(player, CanonicalNbtTest.LIMIT));
        CompoundTag untouched = PlayerInventoryImage.replace(player, Map.of(0, Value.missing()));
        assertEquals(player.getList("Inventory", 10).getCompound(1), untouched.getList("Inventory", 10).getCompound(0));
        assertEquals(1, untouched.getList("Inventory", 10).size());
    }

    @Test void rejectsDuplicateUnknownMistypedAndMalformedInventoryWithoutMutation() throws Exception {
        for (int invalid : new int[]{36, 99, 104, 149, 151, 255}) {
            CompoundTag player = player(); player.getList("Inventory", 10).add(item(invalid, 2));
            assertThrows(IOException.class, () -> PlayerInventoryImage.read(player, 0));
        }
        CompoundTag duplicate = player(); duplicate.getList("Inventory", 10).add(item(0, 1));
        byte[] before = CanonicalNbt.encode(duplicate, CanonicalNbtTest.LIMIT);
        assertThrows(IOException.class, () -> PlayerInventoryImage.read(duplicate, 0));
        assertThrows(IOException.class, () -> PlayerInventoryImage.replace(duplicate, Map.of(0, Value.missing())));
        assertArrayEquals(before, CanonicalNbt.encode(duplicate, CanonicalNbtTest.LIMIT));
        for (String key : new String[]{"Slot", "Count", "id"}) {
            CompoundTag player = player(); player.getList("Inventory", 10).getCompound(0).remove(key);
            assertThrows(IOException.class, () -> PlayerInventoryImage.read(player, 0));
        }
        CompoundTag wrongType = player(); wrongType.getList("Inventory", 10).getCompound(0).putInt("Slot", 0);
        assertThrows(IOException.class, () -> PlayerInventoryImage.read(wrongType, 0));
        CompoundTag absent = player(); absent.remove("Inventory");
        assertThrows(IOException.class, () -> PlayerInventoryImage.read(absent, 0));
        assertThrows(IOException.class, () -> PlayerInventoryImage.replace(player(), Map.of(0, Value.of(new byte[0]))));
        assertThrows(IOException.class, () -> PlayerInventoryImage.replace(player(), Map.of(0, Value.of(CanonicalNbt.encode(new CompoundTag(), CanonicalNbtTest.LIMIT)))));
        assertThrows(IOException.class, () -> PlayerInventoryImage.replace(player(), Map.of(0, Value.of(CanonicalNbt.encode(item(0, 1), CanonicalNbtTest.LIMIT)))));
    }
}
