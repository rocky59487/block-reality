package com.blockreality.impl.server.construction;

import com.blockreality.core.transaction.ConstructionTransaction.Value;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** One exact slot within a complete player document, used by the shared construction owner. */
final class PlayerInventoryParticipant {
    private final Path directory;
    private final UUID actorId;
    private final ServerPlayer live;
    private final int slot;
    private final Value before,after;
    private final CompoundTag baseline,inventoryBefore;
    private CompoundTag working;
    private PlayerFileParticipant files;
    private PlayerFileParticipant.Snapshot snapshot;
    private CompoundTag attempted;
    private boolean uncertain;

    PlayerInventoryParticipant(Path directory,UUID actorId,ServerPlayer live,int slot,Value before,Value after) throws IOException {
        this.directory=directory;this.actorId=actorId;this.live=live;this.slot=slot;this.before=before;this.after=after;
        if(live!=null && !live.getUUID().equals(actorId))throw new IOException("Foreign live player");
        if(slot<0 || slot>40)throw new IOException("Unknown player slot");
        LivePlayerInventory.decode(before);LivePlayerInventory.decode(after);
        files=new PlayerFileParticipant(directory);snapshot=files.capture(actorId);
        if(live==null) {
            if(!snapshot.exists())throw new IOException("Recovery player file is missing");
            baseline=snapshot.data();working=baseline.copy();inventoryBefore=null;
            Value actual=PlayerInventoryImage.read(baseline,slot);
            if(!actual.equals(before) && !actual.equals(after))throw new IOException("Foreign saved inventory participant");
        } else {
            baseline=LivePlayerInventory.player(live);inventoryBefore=LivePlayerInventory.inventory(live);working=baseline.copy();
            if(snapshot.exists())LivePlayerInventory.requirePreservableBaseline(snapshot.data(),baseline);
            if(!PlayerInventoryImage.read(baseline,slot).equals(before))throw new IOException("Live player no longer matches the offer");
        }
    }
    Value read() throws IOException {
        return live==null?PlayerInventoryImage.read(working,slot):LivePlayerInventory.image(live.getInventory().getItem(slot));
    }
    void write(Value desired) throws IOException {
        if(!desired.equals(before) && !desired.equals(after))throw new IOException("Unknown inventory write");
        requireKnownInventory();
        if(live!=null)LivePlayerInventory.apply(live,slot,desired);
        working=PlayerInventoryImage.replace(baseline,Map.of(slot,desired));
        requireKnownInventory();
    }
    void checkpoint() throws IOException {
        if(live==null || !read().equals(before))throw new IOException("Invalid live player checkpoint");
        requireKnownInventory();persist(baseline);
    }
    void flush() throws IOException {
        requireKnownInventory();
        CompoundTag desired=PlayerInventoryImage.replace(baseline,Map.of(slot,read()));
        persist(desired);working=desired;
    }
    void requireKnownInventory() throws IOException {
        Value actual=read();
        if(!actual.equals(before) && !actual.equals(after))throw new IOException("Unknown inventory participant image");
        if(live!=null) {
            if(live.containerMenu!=live.inventoryMenu || !live.containerMenu.getCarried().isEmpty())throw new IOException("Player menu changed during construction");
            CompoundTag expected=PlayerInventoryImage.replace(inventoryBefore,Map.of(slot,actual));
            if(!same(expected,LivePlayerInventory.inventory(live)))throw new IOException("Unrelated inventory changed during construction");
            CompoundTag expectedPlayer=PlayerInventoryImage.replace(baseline,Map.of(slot,actual));
            if(!same(expectedPlayer,LivePlayerInventory.player(live)))throw new IOException("Unrelated player data changed during construction");
        }
    }
    private void persist(CompoundTag desired) throws IOException {
        if(uncertain) {
            PlayerFileParticipant reopened=new PlayerFileParticipant(directory);
            PlayerFileParticipant.Snapshot actual=reopened.verify(actorId);
            boolean known=actual.exists() && (snapshot.exists() && same(actual.data(),snapshot.data()) || attempted!=null && same(actual.data(),attempted));
            if(!known)throw new IOException("Player file changed after an uncertain write");
            files=reopened;snapshot=actual;uncertain=false;
        }
        attempted=desired.copy();
        try {
            // Even an unchanged creative inventory must have its durable baseline acknowledged.
            if(snapshot.exists() && same(snapshot.data(),desired)) {
                PlayerFileParticipant.Snapshot verified=files.verify(actorId);
                if(!verified.exists() || !same(verified.data(),desired))throw new IOException("Player file changed before the barrier");
                snapshot=verified;
            } else snapshot=files.write(snapshot,desired);
        } catch(IOException|RuntimeException|Error failed) {uncertain=true;throw failed;}
        attempted=null;
    }
    private static boolean same(CompoundTag a,CompoundTag b) throws IOException {
        return Arrays.equals(CanonicalNbt.encode(a,CanonicalNbt.MAX_DOCUMENT_BYTES),CanonicalNbt.encode(b,CanonicalNbt.MAX_DOCUMENT_BYTES));
    }
}
