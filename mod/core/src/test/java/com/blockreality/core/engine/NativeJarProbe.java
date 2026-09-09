package com.blockreality.core.engine;

import com.blockreality.core.bsi.BsiContract;
import com.blockreality.core.sidecar.BundledEngine;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** Explicit gate main, not a JUnit test and never shipped. Production classes must come from the jar. */
public final class NativeJarProbe {
    private static final BundledEngine.Loader JAR = BundledNatives.class::getResourceAsStream;
    private static final String OS=System.getProperty("os.name"), ARCH=System.getProperty("os.arch");
    private static void check(boolean good,String message) {if(!good)throw new AssertionError(message);}
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static Optional<Path> unpack(Path root,BundledEngine.Loader loader) {
        return BundledNatives.ensure(root,loader,OS,ARCH,System.out::println);
    }
    private static BundledEngine.Loader altered(String manifest,byte[] bytes) {
        return name -> new ByteArrayInputStream(name.equals(BundledNatives.MANIFEST)
                ? manifest.getBytes(StandardCharsets.UTF_8) : bytes);
    }
    private static void noParts(Path root) throws IOException {
        try(var paths=Files.walk(root)) {check(paths.noneMatch(p->p.toString().endsWith(".part")),"temporary file leaked");}
    }
    private static void faults(Path root) throws Exception {
        String manifest;
        try(var in=JAR.open(BundledNatives.MANIFEST)){manifest=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
        var e=BundledNatives.select(BundledNatives.parse(manifest),OS,ARCH).orElseThrow();
        byte[] bytes;try(var in=JAR.open(e.resource())){bytes=in.readAllBytes();}
        check(sha(bytes).equals(e.sha256()),"real jar payload hash");
        Path target=unpack(root,JAR).orElseThrow();
        var time=Files.getLastModifiedTime(target);
        check(unpack(root,JAR).orElseThrow().equals(target) && time.equals(Files.getLastModifiedTime(target)),"cache rewrote valid bytes");
        String wrong=manifest.replace(BsiContract.sha256(),"f".repeat(64));
        check(unpack(root,altered(wrong,bytes)).isEmpty(),"foreign contract cache adopted");
        check(unpack(root.resolve("foreign"),name->{
            if(name.equals(BundledNatives.MANIFEST))return new ByteArrayInputStream(wrong.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("foreign contract resource opened");
        }).isEmpty(),"foreign contract resource accepted");
        byte[] changed=bytes.clone();changed[changed.length-1]^=1;
        String next=manifest.replace(e.sha256(),sha(changed));
        Path legacy=root.resolve("lib/0123456789abcdef/previous-library");Files.createDirectories(legacy.getParent());Files.write(legacy,bytes);
        Path other=unpack(root,altered(next,changed)).orElseThrow();
        check(!target.equals(other) && sha(Files.readAllBytes(target)).equals(e.sha256()) && Files.exists(legacy),"versions interfered");
        Files.writeString(target,"corrupted cache");check(unpack(root,JAR).orElseThrow().equals(target),"repair path changed");
        check(sha(Files.readAllBytes(target)).equals(e.sha256()),"corrupt cache not repaired");
        for(String mode:List.of("hash","short","overlong","interrupted")) {
            Path failed=root.resolve(mode);
            check(unpack(failed,name->{
                if(name.equals(BundledNatives.MANIFEST))return new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8));
                if(mode.equals("hash"))return new ByteArrayInputStream(changed);
                if(mode.equals("short"))return new ByteArrayInputStream(bytes,0,bytes.length-1);
                if(mode.equals("overlong"))return new SequenceInputStream(new ByteArrayInputStream(bytes),new ByteArrayInputStream(new byte[1]));
                return new FilterInputStream(new ByteArrayInputStream(bytes)) {
                    int remaining=4096;
                    @Override public int read(byte[] b,int off,int len) throws IOException {
                        if(remaining==0)throw new InterruptedIOException("injected real-resource interruption");
                        int n=super.read(b,off,Math.min(len,remaining));remaining-=n;return n;
                    }
                };
            }).isEmpty(),mode+" accepted");
            check(!Files.exists(BundledNatives.targetFor(failed,e)),mode+" published target");noParts(failed);
        }
        if(target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var permissions=Files.getPosixFilePermissions(target);
            check(Collections.disjoint(permissions,Set.of(PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_EXECUTE,PosixFilePermission.OTHERS_EXECUTE)),"execute bit set");
        }
        noParts(root);System.out.println("PASS real bytes: cache adoption, contract precheck, version retention, corruption repair, hash/short/overlong/interruption, temporary cleanup");
    }
    public static void main(String[] args) throws Exception {
        // mode jar cache frame-directory output-directory [race-id]
        String mode=args[0];Path jar=Path.of(args[1]).toRealPath(),root=Path.of(args[2]),frames=Path.of(args[3]),out=Path.of(args[4]);
        Files.createDirectories(out);
        for(Class<?> c:List.of(BundledNatives.class,BsiNative.class,BsiContract.class))
            check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"production class escaped jar: "+c);
        if(mode.equals("missing-pin")) {
            check(!BsiContract.available(),"bad/missing pin considered available");
            check(unpack(root,JAR).isEmpty(),"missing pin unpacked library");System.out.println("PASS missing/bad pin");return;
        }
        check(BsiContract.available(),"jar missing contract resource");
        if(mode.equals("faults")){faults(root);return;}
        if(mode.equals("denied")){check(unpack(root,JAR).isEmpty(),"permission rejection lost");System.out.println("PASS denied cache");return;}
        var loader=JAR;
        if(mode.equals("race")) {
            loader=name->{
                if(!name.equals(BundledNatives.MANIFEST)) {
                    Files.writeString(out.getParent().resolve("ready-"+args[5]),"ready");
                    long deadline=System.nanoTime()+30_000_000_000L;
                    while(!Files.exists(out.getParent().resolve("go"))) {
                        if(System.nanoTime()>deadline)throw new IOException("race rendezvous timeout");
                        try {Thread.sleep(10);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new InterruptedIOException("race interrupted");}
                    }
                }
                return JAR.open(name);
            };
        }
        Path library=unpack(root,loader).orElseThrow();
        int requests=0;
        try(var dirs=Files.list(frames)) {
            for(Path session:dirs.filter(Files::isDirectory).sorted().toList()) {
                Path dest=out.resolve(session.getFileName());Files.createDirectories(dest);
                try(BsiNative engine=BsiNative.open(library,"{\"numThreads\":1}");var inputs=Files.list(session)) {
                    for(Path input:inputs.filter(p->p.toString().endsWith(".frame")).sorted().toList()) {
                        byte[] reply=engine.call(Files.readAllBytes(input));check(reply!=null,"native returned null");
                        Files.write(dest.resolve(input.getFileName()),reply);requests++;
                    }
                }
            }
        }
        check(requests>=12,"no beam/shell corpus requests");noParts(root);
        System.out.println("PASS jar replay "+requests+" frames; "+library+" sha256="+BundledNatives.sha256(library));
    }
}
