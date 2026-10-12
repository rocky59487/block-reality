package com.blockreality.impl.server.construction;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class LivePlayerBaselineTest {
    private static CompoundTag player() {
        CompoundTag root=new CompoundTag();root.put("Inventory",new ListTag());return root;
    }
    private static CompoundTag extension() {
        CompoundTag child=new CompoundTag();child.putString("saved-only","resource");return child;
    }
    private static ListTag list(Tag... children) {
        ListTag result=new ListTag();for(Tag child:children)result.add(child);return result;
    }
    private static void accepted(CompoundTag previous,CompoundTag live) throws Exception {
        byte[] before=CanonicalNbt.encode(previous,CanonicalNbt.MAX_DOCUMENT_BYTES);
        byte[] current=CanonicalNbt.encode(live,CanonicalNbt.MAX_DOCUMENT_BYTES);
        LivePlayerInventory.requirePreservableStructure(previous,live);
        assertArrayEquals(before,CanonicalNbt.encode(previous,CanonicalNbt.MAX_DOCUMENT_BYTES));
        assertArrayEquals(current,CanonicalNbt.encode(live,CanonicalNbt.MAX_DOCUMENT_BYTES));
    }
    private static void refused(CompoundTag previous,CompoundTag live) throws Exception {
        byte[] before=CanonicalNbt.encode(previous,CanonicalNbt.MAX_DOCUMENT_BYTES);
        byte[] current=CanonicalNbt.encode(live,CanonicalNbt.MAX_DOCUMENT_BYTES);
        IOException failure=assertThrows(IOException.class,()->LivePlayerInventory.requirePreservableStructure(previous,live));
        assertTrue(failure.getMessage().contains("normal game saves own schema removal"));
        assertArrayEquals(before,CanonicalNbt.encode(previous,CanonicalNbt.MAX_DOCUMENT_BYTES));
        assertArrayEquals(current,CanonicalNbt.encode(live,CanonicalNbt.MAX_DOCUMENT_BYTES));
    }
    @Test void refusesSavedOnlyRootAndNestedForgeFieldsWithoutMerging() throws Exception {
        for(String parent:new String[]{"ForgeData","ForgeCaps"}) {
            CompoundTag previous=player();previous.put(parent,extension());
            CompoundTag live=previous.copy();live.getCompound(parent).remove("saved-only");
            refused(previous,live);
            previous.getCompound(parent).put("mod:capability",extension());
            live=previous.copy();live.getCompound(parent).getCompound("mod:capability").remove("saved-only");
            refused(previous,live);
            live=previous.copy();live.remove(parent);refused(previous,live);
        }
    }
    @Test void refusesScalarTypeChangesAndContainersReplacedByScalars() throws Exception {
        Tag[] values={ByteTag.ONE,ShortTag.valueOf((short)1),IntTag.valueOf(1),LongTag.valueOf(1),
                FloatTag.valueOf(1),DoubleTag.valueOf(1),StringTag.valueOf("x"),
                new ByteArrayTag(new byte[]{1}),new IntArrayTag(new int[]{1}),new LongArrayTag(new long[]{1}),
                extension(),list(extension())};
        for(Tag value:values) {
            CompoundTag previous=player();previous.put("value",value);
            CompoundTag live=previous.copy();live.put("value",value.getId()==Tag.TAG_INT?ByteTag.ONE:IntTag.valueOf(1));
            refused(previous,live);
        }
        CompoundTag previous=player();previous.put("value",StringTag.valueOf("x"));
        CompoundTag live=previous.copy();live.put("value",extension());refused(previous,live);
    }
    @Test void checksEveryCompoundListElementIncludingLaterNestedKeys() throws Exception {
        CompoundTag previous=player();previous.put("records",list(extension(),extension(),extension()));
        for(int index=0;index<3;index++) {
            CompoundTag live=previous.copy();live.getList("records",Tag.TAG_COMPOUND).getCompound(index).remove("saved-only");
            refused(previous,live);
            live=previous.copy();live.getList("records",Tag.TAG_COMPOUND).getCompound(index).putInt("saved-only",1);
            refused(previous,live);
        }
    }
    @Test void refusesCompoundListResizeClearAndAmbiguousReordering() throws Exception {
        CompoundTag previous=player();previous.put("records",list(extension(),new CompoundTag()));
        CompoundTag live=previous.copy();live.getList("records",Tag.TAG_COMPOUND).remove(1);refused(previous,live);
        live=previous.copy();live.getList("records",Tag.TAG_COMPOUND).add(new CompoundTag());refused(previous,live);
        live=previous.copy();live.put("records",new ListTag());refused(previous,live);
        live=previous.copy();live.put("records",list(new CompoundTag(),extension()));refused(previous,live);
        live=previous.copy();live.put("records",list(StringTag.valueOf("resource")));refused(previous,live);
    }
    @Test void traversesNestedListsAndRefusesTheirLostKeysOrContainerLengths() throws Exception {
        CompoundTag previous=player();previous.put("records",list(list(extension()),list(extension(),extension())));
        CompoundTag live=previous.copy();
        ((ListTag)live.getList("records",Tag.TAG_LIST).get(1)).getCompound(1).remove("saved-only");refused(previous,live);
        live=previous.copy();((ListTag)live.getList("records",Tag.TAG_LIST).get(1)).remove(1);refused(previous,live);
        live=previous.copy();live.getList("records",Tag.TAG_LIST).remove(1);refused(previous,live);
        live=previous.copy();live.getList("records",Tag.TAG_LIST).set(1,list(IntTag.valueOf(1)));refused(previous,live);
    }
    @Test void acceptsChangedScalarValuesAndNewLiveFieldsWithoutFreezingHealthOrPosition() throws Exception {
        CompoundTag previous=player();previous.putFloat("Health",20);previous.putInt("Score",1);
        previous.putByte("Count",(byte)2);previous.putShort("Air",(short)300);previous.putLong("time",1);
        previous.putString("text","old");previous.putDouble("coordinate",1);previous.put("ForgeData",extension());
        CompoundTag live=previous.copy();live.putFloat("Health",10);live.putInt("Score",2);
        live.putByte("Count",(byte)1);live.putShort("Air",(short)250);live.putLong("time",2);
        live.putString("text","new");live.putDouble("coordinate",2);
        live.getCompound("ForgeData").putString("saved-only","updated");live.put("new-capability",extension());
        accepted(previous,live);
    }
    @Test void acceptsArrayAndScalarListChangesIncludingEmptyLists() throws Exception {
        Tag[] previousValues={new ByteArrayTag(new byte[]{1}),new IntArrayTag(new int[]{1}),new LongArrayTag(new long[]{1}),
                list(IntTag.valueOf(1)),list(DoubleTag.valueOf(1)),list(StringTag.valueOf("x")),list(new ByteArrayTag(new byte[]{1}))};
        Tag[] liveValues={new ByteArrayTag(new byte[]{2,3}),new IntArrayTag(new int[0]),new LongArrayTag(new long[]{2,3}),
                list(IntTag.valueOf(2),IntTag.valueOf(3)),new ListTag(),list(StringTag.valueOf("y")),list(new ByteArrayTag(new byte[0]))};
        for(int i=0;i<previousValues.length;i++) {
            CompoundTag previous=player();previous.put("value",previousValues[i]);
            CompoundTag live=previous.copy();live.put("value",liveValues[i]);accepted(previous,live);
        }
        CompoundTag previous=player();previous.put("records",new ListTag());
        CompoundTag live=previous.copy();live.put("records",list(extension()));accepted(previous,live);
    }
    @Test void refusesNonemptyScalarListElementTypeChanges() throws Exception {
        CompoundTag previous=player();previous.put("records",list(IntTag.valueOf(1)));
        CompoundTag live=previous.copy();live.put("records",list(LongTag.valueOf(1)));refused(previous,live);
        live=previous.copy();live.put("records",list(extension()));refused(previous,live);
    }
    @Test void acceptsIndexCompatibleContainerShapesAndNestedScalarUpdates() throws Exception {
        CompoundTag previous=player();previous.put("records",list(list(IntTag.valueOf(1)),list(extension())));
        CompoundTag live=previous.copy();live.getList("records",Tag.TAG_LIST).set(0,list(IntTag.valueOf(2),IntTag.valueOf(3)));
        ((ListTag)live.getList("records",Tag.TAG_LIST).get(1)).getCompound(0).putString("saved-only","updated");
        accepted(previous,live);
    }
    @Test void permitsCountOnlyInventoryShapeUpdatesWithoutAlteringOtherNbt() throws Exception {
        CompoundTag item=new CompoundTag();item.putString("id","minecraft:stone");item.putByte("Slot",(byte)0);
        item.putByte("Count",(byte)2);item.put("tag",extension());
        CompoundTag previous=player();previous.put("Inventory",list(item));
        CompoundTag live=previous.copy();live.getList("Inventory",Tag.TAG_COMPOUND).getCompound(0).putByte("Count",(byte)1);
        accepted(previous,live);
        live.getList("Inventory",Tag.TAG_COMPOUND).getCompound(0).getCompound("tag").remove("saved-only");
        refused(previous,live);
    }
    @Test void enforcesDepthLimitBeforeRecursiveComparisonIncludingNewLiveSubtrees() throws Exception {
        CompoundTag deepest=player(),tip=deepest;
        for(int i=1;i<CanonicalNbt.MAX_DEPTH;i++) {CompoundTag child=new CompoundTag();tip.put("a",child);tip=child;}
        accepted(deepest,deepest.copy());
        tip.put("a",new CompoundTag());
        assertThrows(IOException.class,()->LivePlayerInventory.requirePreservableStructure(deepest,player()));
        assertThrows(IOException.class,()->LivePlayerInventory.requirePreservableStructure(player(),deepest));
        for(int i=0;i<2000;i++) {CompoundTag child=new CompoundTag();tip.put("a",child);tip=child;}
        assertThrows(IOException.class,()->LivePlayerInventory.requirePreservableStructure(deepest,player()));
        CompoundTag cycle=player();cycle.put("cycle",cycle);
        assertThrows(IOException.class,()->LivePlayerInventory.requirePreservableStructure(player(),cycle));
    }
    @Test void enforcesWholeDocumentSizeAndTagLimitsForBothBaselines() {
        CompoundTag oversized=player();oversized.putByteArray("payload",new byte[CanonicalNbt.MAX_DOCUMENT_BYTES]);
        assertThrows(IOException.class,()->LivePlayerInventory.requirePreservableStructure(oversized,player()));
        assertThrows(IOException.class,()->LivePlayerInventory.requirePreservableStructure(player(),oversized));
        ListTag children=new ListTag();for(int i=0;i<CanonicalNbt.MAX_TAGS;i++)children.add(ByteTag.ZERO);
        CompoundTag excessive=player();excessive.put("payload",children);
        assertThrows(IOException.class,()->LivePlayerInventory.requirePreservableStructure(excessive,player()));
        assertThrows(IOException.class,()->LivePlayerInventory.requirePreservableStructure(player(),excessive));
    }
}
