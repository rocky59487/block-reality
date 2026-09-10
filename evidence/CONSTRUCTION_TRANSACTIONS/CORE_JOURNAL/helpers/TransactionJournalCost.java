package com.blockreality.core.transaction;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import java.util.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;
public final class TransactionJournalCost {
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]); if(Files.exists(root))throw new IllegalArgumentException("Fresh evidence directory required");Files.createDirectories(root);
    var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    if(!bean.isThreadAllocatedMemorySupported())throw new IllegalStateException("Allocation counter unavailable");
    bean.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().getId();
    var rows=new ArrayList<String>();rows.add("cells,index,warmup,record_bytes,create_decide_ns,allocated_bytes,replay_ns");
    for(int cells:new int[]{1,128,4096}){
      UUID domain=new UUID(30,cells);long totalNs=0;
      try(var journal=new FileTransactionJournal(root.resolve("cells-"+cells),domain)){
        for(int i=0;i<48;i++){
          var request=new Request(new UUID(40,i),new UUID(2,2),new UUID(3,3),domain,i,"ab".repeat(32));
          var changes=new ArrayList<Change>();
          for(int cell=0;cell<cells;cell++)changes.add(new Change("world/"+cell,Value.missing(),Value.of(("steel/x/"+cell+"/"+"p".repeat(48)).getBytes(StandardCharsets.UTF_8))));
          changes.add(new Change("revision",Value.of((""+i).getBytes(StandardCharsets.UTF_8)),Value.of((""+(i+1)).getBytes(StandardCharsets.UTF_8))));
          var entry=Entry.prepared(new Intent(request,changes,List.of(new UUID(50,i)),List.of()));
          long allocation=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();
          journal.create(entry);var committed=journal.decide(request.id(),Phase.COMMITTED,Reason.NONE);
          long elapsed=System.nanoTime()-start;allocation=bean.getThreadAllocatedBytes(tid)-allocation;
          start=System.nanoTime();var replay=journal.read(request.id()).orElseThrow();long replayNs=System.nanoTime()-start;
          if(!committed.equals(replay))throw new AssertionError("Replay changed images");
          int bytes=TransactionCodec.encode(committed).length;
          rows.add(cells+","+i+","+(i<8)+","+bytes+","+elapsed+","+allocation+","+replayNs);
          totalNs+=elapsed;
        }
      }
      System.out.println("Recorded "+cells+" cells, 8 warmup + 40 measured journal create/decide/replay; total_ns="+totalNs);
    }
    Files.write(root.resolve("samples.csv"),rows);
    Files.writeString(root.resolve("scope.txt"),"Journal-only canonical synthetic images and file barriers. No Forge world/player flush, full construction validation, UI, native analysis, FPS, or acceptance performance claim. Input Intent construction is outside the timed interval.\nJDK="+System.getProperty("java.runtime.version")+"\nOS="+System.getProperty("os.name")+"\n");
  }
}
