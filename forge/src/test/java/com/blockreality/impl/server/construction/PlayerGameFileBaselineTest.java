package com.blockreality.impl.server.construction;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerGameFileBaselineTest {
    @TempDir Path directory;
    UUID actor=UUID.randomUUID();
    CompoundTag player() {
        CompoundTag data=new CompoundTag();data.putUUID("UUID",actor);
        data.putString("Dimension","minecraft:overworld");data.putInt("HealthWitness",20);
        return data;
    }
    PlayerFileParticipant.Snapshot save(CompoundTag data) throws Exception {
        var files=new PlayerFileParticipant(directory);
        return files.write(files.capture(actor),data);
    }
    @AfterEach void clear() {PlayerGameFileBaseline.forget(actor);}
    @Test void absentRecordAndLogoutRejectButNextConsistentBoundaryReestablishes() throws Exception {
        var saved=save(player());
        assertThrows(IOException.class,()->PlayerGameFileBaseline.requireTrusted(directory,actor,saved));
        PlayerGameFileBaseline.recordBoundary(directory,actor,saved,player());
        PlayerGameFileBaseline.requireTrusted(directory,actor,saved);
        clear();assertThrows(IOException.class,()->PlayerGameFileBaseline.requireTrusted(directory,actor,saved));
        PlayerGameFileBaseline.recordBoundary(directory,actor,saved,player());
        PlayerGameFileBaseline.requireTrusted(directory,actor,saved);clear();
    }
    @Test void rawExternalEditRefusesEvenIfShapeIsRepresentableAndDoesNotTouchFile() throws Exception {
        var saved=save(player());PlayerGameFileBaseline.recordBoundary(directory,actor,saved,player());
        CompoundTag outside=player();outside.putInt("HealthWitness",21);
        var rewritten=save(outside);byte[] raw=Files.readAllBytes(directory.resolve(actor+".dat"));
        assertThrows(IOException.class,()->PlayerGameFileBaseline.requireTrusted(directory,actor,rewritten));
        assertArrayEquals(raw,Files.readAllBytes(directory.resolve(actor+".dat")));clear();
    }
    @Test void boundaryDetectsNestedSavedOnlyExtensionButNotChangesDuringOrdinaryPlay() throws Exception {
        var saved=save(player());PlayerGameFileBaseline.recordBoundary(directory,actor,saved,player());
        CompoundTag live=player();live.putInt("HealthWitness",19);
        PlayerGameFileBaseline.requireTrusted(directory,actor,saved);
        CompoundTag outside=player();CompoundTag forge=new CompoundTag();forge.putString("audit_saved_only","resource");outside.put("ForgeData",forge);
        var edited=save(outside);
        assertThrows(IOException.class,()->PlayerGameFileBaseline.recordBoundary(directory,actor,edited,live));
        assertThrows(IOException.class,()->PlayerGameFileBaseline.requireTrusted(directory,actor,edited));
        PlayerGameFileBaseline.recordBoundary(directory,actor,edited,edited.data());
        PlayerGameFileBaseline.requireTrusted(directory,actor,edited);clear();
    }
    @Test void successiveParticipantWritesRebindDigestOnlyOnDurableAcknowledgment() throws Exception {
        var old=save(player());PlayerGameFileBaseline.recordBoundary(directory,actor,old,player());
        CompoundTag next=player();next.putInt("HealthWitness",18);
        var written=save(next);
        assertThrows(IOException.class,()->PlayerGameFileBaseline.requireTrusted(directory,actor,written));
        PlayerGameFileBaseline.acknowledgeWrite(directory,actor,written,true);
        PlayerGameFileBaseline.requireTrusted(directory,actor,written);
        var again=save(player());PlayerGameFileBaseline.acknowledgeWrite(directory,actor,again,true);
        PlayerGameFileBaseline.requireTrusted(directory,actor,again);clear();
    }
    @Test void offlineWriteNeverCreatesGameOwnedRecordOrClearsUnrepresentedState() throws Exception {
        var old=save(player());PlayerGameFileBaseline.acknowledgeWrite(directory,actor,old,false);
        assertThrows(IOException.class,()->PlayerGameFileBaseline.requireTrusted(directory,actor,old));
        CompoundTag outside=player();outside.putInt("new_key",1);var edited=save(outside);
        assertThrows(IOException.class,()->PlayerGameFileBaseline.recordBoundary(directory,actor,edited,player()));
        PlayerGameFileBaseline.acknowledgeWrite(directory,actor,edited,false);
        assertThrows(IOException.class,()->PlayerGameFileBaseline.requireTrusted(directory,actor,edited));clear();
    }
    @Test void rawOnlyRewriteStillRejectsAndForeignDirectoryDoesNotAuthorize() throws Exception {
        var old=save(player());PlayerGameFileBaseline.recordBoundary(directory,actor,old,player());
        assertThrows(IOException.class,()->PlayerGameFileBaseline.requireTrusted(directory.resolve("foreign"),actor,old));
        Path file=directory.resolve(actor+".dat");byte[] bytes=Files.readAllBytes(file);
        bytes[4]^=1;Files.write(file,bytes); // GZIP header timestamp, not the canonical document
        var edited=new PlayerFileParticipant(directory).capture(actor);
        assertEquals(old.data(),edited.data());
        assertThrows(IOException.class,()->PlayerGameFileBaseline.requireTrusted(directory,actor,edited));
        assertArrayEquals(bytes,Files.readAllBytes(file));
    }
    @Test void postReplacementFailureNeverRebindsDigestBeforeDurableSuccess() throws Exception {
        var old=save(player());PlayerGameFileBaseline.recordBoundary(directory,actor,old,player());
        CompoundTag next=player();next.putInt("HealthWitness",18);
        var faulty=new PlayerFileParticipant(directory,stage->{
            if(stage==PlayerFileParticipant.Stage.REPLACED)throw new IOException("after replace, before barrier");
        });
        assertThrows(IOException.class,()->faulty.write(old,next));
        var changed=new PlayerFileParticipant(directory).capture(actor);
        assertEquals(next,changed.data());
        assertThrows(IOException.class,()->PlayerGameFileBaseline.requireTrusted(directory,actor,changed));
        var verified=new PlayerFileParticipant(directory).verify(actor);
        PlayerGameFileBaseline.acknowledgeWrite(directory,actor,verified,true);
        PlayerGameFileBaseline.requireTrusted(directory,actor,verified);
    }
}
