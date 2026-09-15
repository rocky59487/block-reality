package com.blockreality.core.engine;

import com.blockreality.core.bsi.BsiContract;
import com.blockreality.core.sidecar.BundledEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen NATIVE_CONSUMER guards; real release bytes are exercised by NativeJarProbe. */
class NativeCacheTest {
    @TempDir Path root;
    private static final byte[] BYTES = new byte[8192];
    private static String manifest(byte[] bytes) throws Exception {
        return "linux x86_64 libbsi_tectonic.so " + HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                + " " + bytes.length + " 1.3.0 " + BsiContract.sha256() + "\n";
    }
    private static BundledEngine.Loader jar(String manifest, byte[] bytes) {
        return name -> new ByteArrayInputStream(name.equals(BundledNatives.MANIFEST)
                ? manifest.getBytes(StandardCharsets.UTF_8) : bytes);
    }
    private Path unpack(byte[] bytes) throws Exception {
        return BundledNatives.ensure(root, jar(manifest(bytes),bytes),"Linux","amd64",s -> {}).orElseThrow();
    }
    @Test void contractMismatchIsRejectedBeforeOpeningLibraryOrAdoptingCache() throws Exception {
        unpack(BYTES);
        String wrong = manifest(BYTES).replace(BsiContract.sha256(),"f".repeat(64));
        AtomicInteger opened = new AtomicInteger(); List<String> log = new ArrayList<>();
        var loader = jar(wrong,BYTES);
        assertTrue(BundledNatives.ensure(root, name -> {
            if (!name.equals(BundledNatives.MANIFEST)) opened.incrementAndGet();
            return loader.open(name);
        }, "Linux","amd64",log::add).isEmpty());
        assertEquals(0,opened.get()); assertTrue(log.toString().contains("contract"));
    }
    @Test void duplicatePlatformIsRejected() throws Exception {
        String line=manifest(BYTES);
        assertThrows(IllegalArgumentException.class, () -> BundledNatives.parse(line+line));
    }
    @Test void fullHashAndPlatformArePartOfCacheIdentity() throws Exception {
        var e=BundledNatives.parse(manifest(BYTES)).get(0);
        assertEquals(root.resolve("lib/linux-x86_64").resolve(e.sha256()).resolve(e.fileName()),BundledNatives.targetFor(root,e));
        var collision=new BundledNatives.Entry(e.os(),e.arch(),e.fileName(),e.shortHash()+"f".repeat(48),e.size(),e.engineVersion(),e.contractSha256());
        assertNotEquals(BundledNatives.targetFor(root,e),BundledNatives.targetFor(root,collision));
        var other=new BundledNatives.Entry("macos",e.arch(),e.fileName(),e.sha256(),e.size(),e.engineVersion(),e.contractSha256());
        assertNotEquals(BundledNatives.targetFor(root,e),BundledNatives.targetFor(root,other));
    }
    @Test void otherVersionsAndLegacyCacheArePreserved() throws Exception {
        Path legacy=root.resolve("lib/0123456789abcdef/old.so");Files.createDirectories(legacy.getParent());Files.write(legacy,BYTES);
        Path first=unpack(BYTES);byte[] next=BYTES.clone();next[5]=1;Path second=unpack(next);
        assertTrue(Files.exists(first));assertTrue(Files.exists(second));assertTrue(Files.exists(legacy));
        assertArrayEquals(BYTES,Files.readAllBytes(first));assertArrayEquals(next,Files.readAllBytes(second));
        assertArrayEquals(BYTES,Files.readAllBytes(legacy));assertNotEquals(first,second);
    }
    @Test void corruptCacheIsRepaired() throws Exception {
        Path p=unpack(BYTES);Files.writeString(p,"broken");assertEquals(p,unpack(BYTES));
        assertArrayEquals(BYTES,Files.readAllBytes(p)); noParts();
    }
    @Test void overlongResourceStopsAtDeclaredSizePlusOne() throws Exception {
        var e=BundledNatives.parse(manifest(BYTES)).get(0);AtomicInteger read=new AtomicInteger();
        Path dest=root.resolve("copy.part");
        assertThrows(IOException.class, () -> BundledNatives.copyVerified(name -> new InputStream() {
            @Override public int read() { return read.incrementAndGet()<=BYTES.length+10000 ? 0 : -1; }
        },e,dest));
        assertEquals(BYTES.length+1,read.get());assertFalse(Files.exists(dest));
    }
    @Test void interruptedStreamPublishesNothingAndCleansTemporary() throws Exception {
        String m=manifest(BYTES);List<String> log=new ArrayList<>();
        assertTrue(BundledNatives.ensure(root,name -> {
            if (name.equals(BundledNatives.MANIFEST)) return new ByteArrayInputStream(m.getBytes(StandardCharsets.UTF_8));
            return new InputStream() {
                int read;
                @Override public int read() throws IOException {
                    if (++read>100) throw new InterruptedIOException("injected interruption");return 0;
                }
            };
        },"Linux","amd64",log::add).isEmpty());
        assertTrue(log.toString().contains("interruption"));noParts();
        assertFalse(Files.exists(BundledNatives.targetFor(root,BundledNatives.parse(m).get(0))));
    }
    @Test void manifestReadIsBounded() {
        AtomicInteger read=new AtomicInteger();List<String> log=new ArrayList<>();
        assertTrue(BundledNatives.ensure(root,name -> new InputStream() {
            @Override public int read() {return read.incrementAndGet()<100000 ? '#' : -1;}
        },"Linux","amd64",log::add).isEmpty());
        assertEquals(65537,read.get());assertTrue(log.toString().contains("manifest"));
    }
    @Test void simultaneousUnpackConverges() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Path>> tasks=new ArrayList<>();
            for(int i=0;i<4;i++)tasks.add(pool.submit(()->{start.await();return unpack(BYTES);}));
            start.countDown();Path p=tasks.get(0).get(15,TimeUnit.SECONDS);
            for(var task:tasks)assertEquals(p,task.get(15,TimeUnit.SECONDS));
            assertArrayEquals(BYTES,Files.readAllBytes(p));noParts();
        } finally {pool.shutdownNow();}
    }
    @Test void deniedDestinationIsRecoverable() throws Exception {
        Files.writeString(root.resolve("lib"),"not a directory");List<String> log=new ArrayList<>();
        assertTrue(BundledNatives.ensure(root,jar(manifest(BYTES),BYTES),"Linux","amd64",log::add).isEmpty());
        assertTrue(log.toString().contains("could not unpack"));noParts();
    }
    @Test void darwinUsesTheSameNormalisationAsManifestSelection() {
        String os=System.getProperty("os.name"),arch=System.getProperty("os.arch");
        try {System.setProperty("os.name","Darwin");System.setProperty("os.arch","x86-64");
            assertEquals("macos-x86_64",EngineLocator.platform());assertEquals("libbsi_tectonic.dylib",EngineLocator.libraryFileName("bsi_tectonic"));
        } finally {System.setProperty("os.name",os);System.setProperty("os.arch",arch);}
    }
    private void noParts() throws IOException {
        try(var files=Files.walk(root)){assertEquals(0,files.filter(p->p.toString().endsWith(".part")).count());}
    }
}
