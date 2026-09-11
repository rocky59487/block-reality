package com.blockreality.core.transaction;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Native/file-journal process recovery; explicitly not Forge or power-loss qualification. */
public final class NativeFractureProcessGate {
    private NativeFractureProcessGate() { }
    public static void main(String[] args) throws Exception {
        if (args.length!=2 || !Path.of(args[0]).isAbsolute() || !Files.isRegularFile(Path.of(args[0])) || !Path.of(args[1]).isAbsolute())
            throw new IllegalArgumentException("Absolute native library and fresh absolute evidence directory required");
        Path output=Path.of(args[1]); if (Files.exists(output)) throw new IllegalArgumentException("Refusing to replace process evidence");
        Files.createDirectories(output); var scenarios=new ArrayList<String>();
        for (String phase:List.of("prepare","commit","abort")) for (var stage:FileTransactionJournal.Stage.values()) scenarios.add(phase+"-"+stage.name().toLowerCase(Locale.ROOT));
        for (int i=1;i<=3;i++) for (String point:List.of("write-","flush-","rollback-write-","rollback-flush-")) scenarios.add(point+i);
        scenarios.addAll(List.of("native-prepared","checkpoint","native-finished","published"));
        long start=System.nanoTime();
        for (String scenario:scenarios) {
            Path root=output.resolve(scenario); Files.createDirectory(root);
            run(args[0],"crash",scenario,root,73); snapshot(root,"before-recovery");
            run(args[0],"recover",scenario,root,0); snapshot(root,"before-later-recovery");
            run(args[0],"later",scenario,root,0); System.out.println("PASS "+scenario);
        }
        String report="{\"status\":\"PASS\",\"crashed_jvms\":"+scenarios.size()+",\"fresh_recovery_jvms\":"+(2*scenarios.size())
                +",\"elapsed_ms\":"+((System.nanoTime()-start)/1000000)+",\"scope\":\"actual native sessions and file participants, not Minecraft or power loss\"}\n";
        Files.writeString(output.resolve("summary.json"),report,StandardOpenOption.CREATE_NEW); System.out.print(report);
    }
    private static void snapshot(Path root,String name) throws Exception {
        Path output=root.resolve(name); Files.createDirectory(output);
        try (var files=Files.walk(root)) {
            for (Path source:files.filter(Files::isRegularFile).filter(p -> !root.relativize(p).getName(0).toString().startsWith("before-")).toList()) {
                Path target=output.resolve(root.relativize(source)); Files.createDirectories(target.getParent()); Files.copy(source,target);
            }
        }
    }
    private static void run(String library,String mode,String scenario,Path root,int expected) throws Exception {
        String suffix=System.getProperty("os.name","").startsWith("Windows")?".exe":"";
        var command=List.of(Path.of(System.getProperty("java.home"),"bin","java"+suffix).toString(),"-cp",System.getProperty("java.class.path"),
                NativeFractureProcessDriver.class.getName(),library,mode,scenario,root.toString());
        Files.write(root.resolve(mode+"-command.txt"),command,StandardOpenOption.CREATE_NEW);
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve(mode+".log").toFile()).start();
        try {
            if (!process.waitFor(30,TimeUnit.SECONDS)) throw new AssertionError("Timeout: "+mode+" "+scenario);
            if (process.exitValue()!=expected) throw new AssertionError("Exit "+process.exitValue()+": "+mode+" "+scenario);
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10,TimeUnit.SECONDS); } }
    }
}
