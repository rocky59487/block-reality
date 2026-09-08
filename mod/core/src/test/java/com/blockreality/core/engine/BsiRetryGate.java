package com.blockreality.core.engine;

import com.blockreality.core.bsi.BsiContract;
import com.sun.jna.Library;
import com.sun.jna.Native;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Standalone native integration gate: javac --release 17, then run with the
 * gate-only counted REAL tectonic adapter library and C4 repeated-world frames.
 * No numerical algorithm or native-call retry loop is duplicated here:
 * production BsiNative.call() must grow its own 64 KiB buffer correctly.
 */
public final class BsiRetryGate {
    private interface Counter extends Library {
        long bsi_retry_test_count(int index);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("library and native fixture directory required");
        Path library=Path.of(args[0]), frames=Path.of(args[1]);
        String hello=new String(Files.readAllBytes(frames.resolve("hello.frame")),java.nio.charset.StandardCharsets.UTF_8);
        if(!BsiContract.available() || !hello.contains(BsiContract.sha256()))
            throw new AssertionError("consumer contract hash differs from native fixture");
        Counter count=Native.load(library.toAbsolutePath().toString(),Counter.class);
        try (BsiNative engine=BsiNative.open(library,
                "{\"probe\":true,\"assumeCaps\":[\"bsi.core\",\"bsi.world.edit\"],\"numThreads\":4}")) {
            for (String name:new String[]{"hello","vocab","world"}) {
                byte[] reply=engine.call(Files.readAllBytes(frames.resolve(name+".frame")));
                if (reply==null) throw new AssertionError(name+" transport failed: "+engine.lastError());
            }
            byte[] oracle=Files.readAllBytes(frames.resolve("oracle.frame"));
            if (oracle.length<=65536) throw new AssertionError("fixture does not exceed default Java buffer");
            long before=count.bsi_retry_test_count(3);
            byte[] actual=engine.call(Files.readAllBytes(frames.resolve("solve.frame")));
            long executions=count.bsi_retry_test_count(3)-before;
            if(executions!=1) throw new AssertionError("RETRY-05 actual solve executions="+executions);
            if(!Arrays.equals(oracle,actual)) throw new AssertionError("RETRY-05 reply differs from independent direct oracle");
            System.out.println("BSI-JNA-RETRY ALL PASS (replyBytes="+actual.length+" solveExecutions="+executions+")");
        }
    }
}
