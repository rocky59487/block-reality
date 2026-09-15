package com.blockreality.impl.server.construction;

import com.blockreality.core.transaction.ConstructionTransaction.Value;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

/** Server-authored BUILD schema. Hashes bind exact participant images without duplicating item NBT. */
record PlacementPlan(UUID offer, UUID actor, String dimension, BlockPos clicked, BlockPos target, Direction face,
                     Vec3 hit, InteractionHand hand, int slot, String product, int axis, boolean creative,
                     String beforeCell, String afterCell, String beforeItem, String afterItem) {
    static final int MAX_BYTES = 4096;

    PlacementPlan {
        Objects.requireNonNull(offer); Objects.requireNonNull(actor); Objects.requireNonNull(face); Objects.requireNonNull(hand); Objects.requireNonNull(hit);
        clicked = checkedPosition(clicked); target = checkedPosition(target);
        resource(dimension); resource(product);
        if (axis < 0 || axis > 2 || hand == InteractionHand.MAIN_HAND && (slot < 0 || slot > 8)
                || hand == InteractionHand.OFF_HAND && slot != 40) throw new IllegalArgumentException("Invalid placement slot/axis");
        if (!Double.isFinite(hit.x) || !Double.isFinite(hit.y) || !Double.isFinite(hit.z)
                || hit.x < clicked.getX() || hit.x > clicked.getX()+1.0
                || hit.y < clicked.getY() || hit.y > clicked.getY()+1.0
                || hit.z < clicked.getZ() || hit.z > clicked.getZ()+1.0)
            throw new IllegalArgumentException("Invalid placement hit");
        for (String hash : List.of(beforeCell,afterCell,beforeItem,afterItem))
            if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid participant digest");
        if (beforeCell.equals(afterCell) || creative != beforeItem.equals(afterItem))
            throw new IllegalArgumentException("Invalid placement debit");
    }

    Value image() throws IOException {
        CompoundTag tag = new CompoundTag(); tag.putInt("format",1); tag.putUUID("offer",offer); tag.putUUID("actor",actor);
        tag.putString("dimension",dimension); tag.putIntArray("clicked",coordinates(clicked)); tag.putIntArray("target",coordinates(target));
        tag.putInt("face",face.ordinal()); tag.putDouble("hitX",hit.x); tag.putDouble("hitY",hit.y); tag.putDouble("hitZ",hit.z);
        tag.putInt("hand",hand.ordinal()); tag.putInt("slot",slot); tag.putString("product",product);
        tag.putInt("axis",axis); tag.putBoolean("creative",creative);
        tag.putString("beforeCell",beforeCell); tag.putString("afterCell",afterCell);
        tag.putString("beforeItem",beforeItem); tag.putString("afterItem",afterItem);
        return Value.of(CanonicalNbt.encode(tag,MAX_BYTES));
    }
    String hash() throws IOException { return digest(image()); }

    static PlacementPlan decode(Value image) throws IOException {
        if (!image.present()) throw new IOException("Placement plan is missing");
        try {
            CompoundTag tag = CanonicalNbt.decode(image.bytes(),MAX_BYTES);
            if (tag.getInt("format") != 1) throw new IOException("Unknown placement schema");
            PlacementPlan plan = new PlacementPlan(tag.getUUID("offer"),tag.getUUID("actor"),tag.getString("dimension"),
                    position(tag.getIntArray("clicked")),position(tag.getIntArray("target")),Direction.values()[tag.getInt("face")],
                    new Vec3(tag.getDouble("hitX"),tag.getDouble("hitY"),tag.getDouble("hitZ")),
                    InteractionHand.values()[tag.getInt("hand")],tag.getInt("slot"),tag.getString("product"),tag.getInt("axis"),tag.getBoolean("creative"),
                    tag.getString("beforeCell"),tag.getString("afterCell"),tag.getString("beforeItem"),tag.getString("afterItem"));
            if (!plan.image().equals(image)) throw new IOException("Noncanonical placement schema");
            return plan;
        } catch (RuntimeException malformed) { throw new IOException("Invalid placement schema",malformed); }
    }

    void verifyImages(Value cellBefore, Value cellAfter, Value itemBefore, Value itemAfter) throws IOException {
        if (!beforeCell.equals(digest(cellBefore)) || !afterCell.equals(digest(cellAfter))
                || !beforeItem.equals(digest(itemBefore)) || !afterItem.equals(digest(itemAfter)))
            throw new IOException("Placement participants do not match the plan");
    }
    static String digest(Value value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update((byte)(value.present()?1:0));
            if (value.present()) digest.update(value.bytes());
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
    private static int[] coordinates(BlockPos pos) { return new int[]{pos.getX(),pos.getY(),pos.getZ()}; }
    private static BlockPos position(int[] coordinates) {
        if (coordinates.length != 3) throw new IllegalArgumentException("Invalid placement coordinates");
        return new BlockPos(coordinates[0],coordinates[1],coordinates[2]);
    }
    private static BlockPos checkedPosition(BlockPos pos) {
        Objects.requireNonNull(pos);
        try { CellStateImage.key(pos); return pos.immutable(); }
        catch (IOException invalid) { throw new IllegalArgumentException("Invalid placement position",invalid); }
    }
    private static void resource(String value) {
        if (value == null || value.length()>128) throw new IllegalArgumentException("Invalid resource name");
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        if (parsed == null || !parsed.toString().equals(value)) throw new IllegalArgumentException("Noncanonical resource name");
    }
}
