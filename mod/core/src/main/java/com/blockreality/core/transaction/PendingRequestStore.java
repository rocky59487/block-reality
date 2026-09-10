package com.blockreality.core.transaction;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Bounded client outbox: acknowledge storage before send; erase only the exact acknowledged request. */
public final class PendingRequestStore {
    public static final int MAX_DESTINATIONS=16,MAX_REQUEST_BYTES=4096;
    private static final int MAX_FILE_BYTES=70000,MAGIC=0x42525031;
    private final Path directory,file,lock;
    public PendingRequestStore(Path directory) {
        this.directory=directory.toAbsolutePath().normalize();file=this.directory.resolve("pending.bin");lock=this.directory.resolve("pending.lock");
    }
    public byte[] get(String destination) throws IOException {
        key(destination);return locked(entries->{byte[] found=entries.get(destination);return found==null?null:found.clone();});
    }
    public void put(String destination,byte[] request) throws IOException {
        key(destination);byte[] copy=request.clone();
        if(copy.length==0 || copy.length>MAX_REQUEST_BYTES)throw new IOException("Pending request capacity");
        locked(entries->{
            byte[] existing=entries.get(destination);
            if(existing!=null && !Arrays.equals(existing,copy))throw new IOException("A different request is unresolved");
            if(existing==null && entries.size()==MAX_DESTINATIONS)throw new IOException("Pending destination capacity");
            entries.put(destination,copy);persist(entries);return null;
        });
    }
    public void remove(String destination,byte[] expected) throws IOException {
        key(destination);byte[] copy=expected.clone();
        locked(entries->{
            byte[] existing=entries.get(destination);
            if(existing==null)return null;
            if(!Arrays.equals(existing,copy))throw new IOException("Pending receipt binding mismatch");
            entries.remove(destination);persist(entries);return null;
        });
    }
    @FunctionalInterface private interface Operation<T> {T run(TreeMap<String,byte[]> entries)throws IOException;}
    private <T>T locked(Operation<T> operation)throws IOException {
        Files.createDirectories(directory);
        if(Files.isSymbolicLink(directory) || Files.isSymbolicLink(file) || Files.isSymbolicLink(lock))throw new IOException("Pending storage must be local regular files");
        try(FileChannel channel=FileChannel.open(lock,CREATE,WRITE);var lease=channel.tryLock()) {
            if(lease==null)throw new IOException("Pending storage is in use");
            return operation.run(read());
        } catch(OverlappingFileLockException busy) {throw new IOException("Pending storage is in use",busy);}
    }
    private TreeMap<String,byte[]> read()throws IOException {
        TreeMap<String,byte[]> entries=new TreeMap<>();
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return entries;
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid pending storage");
        byte[] all;
        try(InputStream input=Files.newInputStream(file)) {all=input.readNBytes(MAX_FILE_BYTES+1);}
        if(all.length>MAX_FILE_BYTES || all.length<40)throw new IOException("Invalid pending storage size");
        byte[] payload=Arrays.copyOf(all,all.length-32);
        if(!MessageDigest.isEqual(digest(payload),Arrays.copyOfRange(all,all.length-32,all.length)))throw new IOException("Corrupt pending storage");
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(payload))) {
            if(input.readInt()!=MAGIC)throw new IOException("Unknown pending storage version");
            int count=input.readInt();if(count<0 || count>MAX_DESTINATIONS)throw new IOException("Pending destination capacity");
            String previous="";
            for(int i=0;i<count;i++) {
                String destination=input.readUTF();key(destination);
                if(destination.compareTo(previous)<=0)throw new IOException("Noncanonical pending destinations");
                previous=destination;
                int size=input.readInt();if(size<1 || size>MAX_REQUEST_BYTES)throw new IOException("Pending request capacity");
                byte[] request=input.readNBytes(size);if(request.length!=size)throw new EOFException("Truncated pending request");
                entries.put(destination,request);
            }
            if(input.read()!=-1)throw new IOException("Trailing pending storage data");
        }
        return entries;
    }
    private void persist(TreeMap<String,byte[]> entries)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream output=new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);output.writeInt(entries.size());
            for(var entry:entries.entrySet()) {output.writeUTF(entry.getKey());output.writeInt(entry.getValue().length);output.write(entry.getValue());}
        }
        byte[] payload=bytes.toByteArray();bytes.writeBytes(digest(payload));byte[] expected=bytes.toByteArray();
        Path temporary=Files.createTempFile(directory,"pending-",".tmp");
        try {
            try(FileChannel channel=FileChannel.open(temporary,WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(expected);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(FileChannel channel=FileChannel.open(file,WRITE)) {channel.force(true);}
            if(Files.getFileStore(directory).supportsFileAttributeView("posix"))
                try(FileChannel channel=FileChannel.open(directory,READ)) {channel.force(true);}
            byte[] actual;
            try(InputStream input=Files.newInputStream(file)) {actual=input.readNBytes(MAX_FILE_BYTES+1);}
            if(!Arrays.equals(expected,actual))throw new IOException("Pending storage readback mismatch");
        } finally {Files.deleteIfExists(temporary);}
    }
    private static byte[] digest(byte[] bytes) {
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}
        catch(java.security.NoSuchAlgorithmException missing){throw new AssertionError(missing);}
    }
    private static void key(String key)throws IOException {if(key==null || !key.matches("[0-9a-f]{64}"))throw new IOException("Invalid pending destination");}
}
