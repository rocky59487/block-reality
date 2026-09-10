package com.blockreality.core.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PendingRequestStoreTest {
    @TempDir Path directory;
    private static String key(int i) {return String.format(Locale.ROOT,"%064x",i);}
    @Test void reopensExactPendingRequestAndReceiptCannotEraseAnotherBinding()throws Exception {
        PendingRequestStore store=new PendingRequestStore(directory);byte[] request={3,7,8,99};
        assertNull(store.get(key(1)));store.put(key(1),request);request[0]=88;
        PendingRequestStore restarted=new PendingRequestStore(directory);
        assertArrayEquals(new byte[]{3,7,8,99},restarted.get(key(1)));
        byte[] stored=Files.readAllBytes(directory.resolve("pending.bin"));
        assertThrows(IOException.class,()->restarted.put(key(1),request));
        assertThrows(IOException.class,()->restarted.remove(key(1),request));
        assertArrayEquals(stored,Files.readAllBytes(directory.resolve("pending.bin")));
        for(int i=0;i<100;i++)restarted.put(key(1),new byte[]{3,7,8,99});
        assertArrayEquals(stored,Files.readAllBytes(directory.resolve("pending.bin")));
        restarted.remove(key(1),new byte[]{3,7,8,99});
        assertNull(new PendingRequestStore(directory).get(key(1)));
    }
    @Test void capacitiesRefuseWithoutEvictionOrDataLoss()throws Exception {
        PendingRequestStore store=new PendingRequestStore(directory);
        for(int i=0;i<16;i++){byte[] payload=new byte[4096];Arrays.fill(payload,(byte)i);store.put(key(i),payload);}
        byte[] stored=Files.readAllBytes(directory.resolve("pending.bin"));
        assertThrows(IOException.class,()->store.put(key(17),new byte[]{1}));
        assertThrows(IOException.class,()->store.put(key(1),new byte[4097]));
        assertThrows(IOException.class,()->store.put(key(1),new byte[0]));
        assertThrows(IOException.class,()->store.get("../elsewhere"));
        assertArrayEquals(stored,Files.readAllBytes(directory.resolve("pending.bin")));
        for(int i=0;i<16;i++){byte[] expected=new byte[4096];Arrays.fill(expected,(byte)i);assertArrayEquals(expected,store.get(key(i)));}
    }
    @Test void corruptOversizedAndTruncatedFilesRefuseAndRetainOriginals()throws Exception {
        PendingRequestStore store=new PendingRequestStore(directory);store.put(key(1),new byte[]{8,5,3});
        Path file=directory.resolve("pending.bin");byte[] valid=Files.readAllBytes(file),corrupt=valid.clone();corrupt[11]^=1;
        for(byte[] invalid:List.of(corrupt,Arrays.copyOf(valid,valid.length-1),new byte[70001],new byte[0])) {
            Files.write(file,invalid);
            assertThrows(IOException.class,()->store.get(key(1)));
            assertThrows(IOException.class,()->store.put(key(2),new byte[]{9}));
            assertThrows(IOException.class,()->store.remove(key(1),new byte[]{8,5,3}));
            assertArrayEquals(invalid,Files.readAllBytes(file));
        }
    }
    @Test void anotherClientOwnerCannotRaceTheStorageBarrier()throws Exception {
        PendingRequestStore store=new PendingRequestStore(directory);store.put(key(1),new byte[]{4});
        byte[] before=Files.readAllBytes(directory.resolve("pending.bin"));
        try(var channel=FileChannel.open(directory.resolve("pending.lock"),StandardOpenOption.WRITE);var lock=channel.lock()) {
            assertThrows(IOException.class,()->store.put(key(2),new byte[]{5}));
            assertThrows(IOException.class,()->store.remove(key(1),new byte[]{4}));
        }
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("pending.bin")));
        assertArrayEquals(new byte[]{4},store.get(key(1)));
    }
}
