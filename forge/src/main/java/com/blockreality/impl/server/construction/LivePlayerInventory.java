package com.blockreality.impl.server.construction;

import com.blockreality.core.transaction.ConstructionTransaction.Value;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.MAX_VALUE_BYTES;

/** Exact live inventory binding. Applying a slot does not invoke menus, packets or player load callbacks. */
final class LivePlayerInventory {
    private LivePlayerInventory() { }

    static Value image(ItemStack stack) throws IOException {
        if (stack.isEmpty()) return Value.missing();
        try { return Value.of(CanonicalNbt.encode(stack.save(new CompoundTag()),MAX_VALUE_BYTES)); }
        catch (RuntimeException invalid) { throw new IOException("Item serialization failed",invalid); }
    }
    static ItemStack decode(Value image) throws IOException {
        if (!image.present()) return ItemStack.EMPTY;
        try {
            CompoundTag tag = CanonicalNbt.decode(image.bytes(),MAX_VALUE_BYTES);
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("id"));
            if (id == null || !id.toString().equals(tag.getString("id")) || !BuiltInRegistries.ITEM.containsKey(id)
                    || !tag.contains("Count",Tag.TAG_BYTE) || tag.getByte("Count") <= 0)
                throw new IOException("Unknown or invalid item image");
            ItemStack result = ItemStack.of(tag.copy());
            if (result.isEmpty() || result.getCount()>result.getMaxStackSize() || !image(result).equals(image))
                throw new IOException("Item data does not round-trip exactly");
            return result;
        } catch (RuntimeException invalid) { throw new IOException("Item deserialization failed",invalid); }
    }
    static Value debitOne(Value before) throws IOException {
        ItemStack source = decode(before);
        if (source.isEmpty()) throw new IOException("No material to consume");
        if (source.getCount() == 1) return Value.missing();
        CompoundTag tag = CanonicalNbt.decode(before.bytes(),MAX_VALUE_BYTES); tag.putByte("Count",(byte)(source.getCount()-1));
        Value result = Value.of(CanonicalNbt.encode(tag,MAX_VALUE_BYTES)); decode(result); return result;
    }
    static CompoundTag inventory(ServerPlayer actor) throws IOException {
        try {
            CompoundTag result = new CompoundTag(); result.put("Inventory",actor.getInventory().save(new ListTag()));
            result.putInt("SelectedItemSlot",actor.getInventory().selected);
            for (int slot=0;slot<41;slot++) decode(PlayerInventoryImage.read(result,slot));
            CanonicalNbt.encode(result,CanonicalNbt.MAX_DOCUMENT_BYTES); return result;
        } catch (RuntimeException invalid) { throw new IOException("Live inventory capture failed",invalid); }
    }
    static CompoundTag player(ServerPlayer actor) throws IOException {
        if (actor.containerMenu != actor.inventoryMenu || !actor.containerMenu.getCarried().isEmpty())
            throw new IOException("Close the inventory/container before placement");
        try {
            CompoundTag inventory = inventory(actor);
            CompoundTag full = actor.saveWithoutId(new CompoundTag());
            if (!full.hasUUID("UUID") || !full.getUUID("UUID").equals(actor.getUUID())
                    || !full.getString("Dimension").equals(actor.serverLevel().dimension().location().toString())
                    || !Objects.equals(full.get("Inventory"),inventory.get("Inventory"))
                    || full.getInt("SelectedItemSlot") != actor.getInventory().selected)
                throw new IOException("Live player identity/inventory capture mismatch");
            if (actor.getWardenSpawnTracker().isPresent()) {
                Tag tracker = net.minecraft.world.entity.monster.warden.WardenSpawnTracker.CODEC
                        .encodeStart(NbtOps.INSTANCE,actor.getWardenSpawnTracker().orElseThrow()).result()
                        .orElseThrow(() -> new IOException("Player tracker serialization was incomplete"));
                if (!tracker.equals(full.get("warden_spawn_tracker"))) throw new IOException("Player tracker witness mismatch");
            }
            if (actor.getRespawnPosition()!=null && !actor.getRespawnDimension().location().toString().equals(full.getString("SpawnDimension")))
                throw new IOException("Player spawn dimension serialization was incomplete");
            NbtUtils.addCurrentDataVersion(full);
            CanonicalNbt.encode(full,CanonicalNbt.MAX_DOCUMENT_BYTES); return full;
        } catch (RuntimeException invalid) { throw new IOException("Live player capture failed",invalid); }
    }
    static void requirePreservableBaseline(CompoundTag previous, CompoundTag live) throws IOException {
        // A transaction may checkpoint current live data, but cannot silently drop an unrepresented
        // saved extension. Normal game saves own legitimate schema removal, not this adapter.
        if (!live.getAllKeys().containsAll(previous.getAllKeys())) throw new IOException("Saved player fields are not represented by the live baseline");
        for (int slot=0;slot<41;slot++) decode(PlayerInventoryImage.read(previous,slot));
    }
    static void apply(ServerPlayer actor, int slot, Value desired) throws IOException {
        ItemStack replacement = decode(desired);
        CompoundTag before = inventory(actor);
        CompoundTag expected = PlayerInventoryImage.replace(before,Map.of(slot,desired));
        actor.getInventory().setItem(slot,replacement);
        CompoundTag actual = inventory(actor);
        if (!Arrays.equals(CanonicalNbt.encode(expected,CanonicalNbt.MAX_DOCUMENT_BYTES),CanonicalNbt.encode(actual,CanonicalNbt.MAX_DOCUMENT_BYTES)))
            throw new IOException("Live inventory write affected unexpected data");
    }
}
