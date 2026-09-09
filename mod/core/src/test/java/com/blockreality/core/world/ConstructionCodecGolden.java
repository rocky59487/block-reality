package com.blockreality.core.world;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.engine.GameVocabulary;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Test-only fixture generator; run on the old encoder before changing it. */
public final class ConstructionCodecGolden {
    private ConstructionCodecGolden() { }
    static ConstructionLedger fixture() {
        var declarations = new LinkedHashMap<BlockKey,ConstructionDeclaration>();
        var records = new TreeMap<Long,ConstructionLedger.Artifact>(); long id=1;
        var products = GameVocabulary.products().keySet().stream().sorted(Comparator.comparing(GameVocabulary.Product::material)
                .thenComparing(GameVocabulary.Product::section)).toList();
        for (var product : products) for (int axis=-1;axis<=2;axis++) {
            var pos=id==1?new BlockKey(-30000000,-2048,-30000000):id==2?new BlockKey(29999999,2047,29999999)
                    :new BlockKey((int)id-20,(int)id-10,(int)id*17-256);
            var declaration=new ConstructionDeclaration(product.material(),product.section(),axis);
            declarations.put(pos,declaration);
            var parents=id==1?List.<Long>of():id==2?List.of(1L):List.of(1L,id-1);
            records.put(id,new ConstructionLedger.Artifact(id,declaration,parents,id==1?List.of():List.of(pos)));id++;
        }
        var ledger=new ConstructionLedger(new ConstructionLedger.Graph(UUID.fromString("6e5767c2-251a-4571-92f3-8bbf02169e55"),id,records));
        ledger.cells.putAll(declarations); ledger.destroyed.addAll(declarations.keySet().stream().limit(3).toList());
        ledger.epoch=Long.MAX_VALUE-1;ledger.completedEpoch=Long.MAX_VALUE-99;
        ledger.failure="refused \0 Ω \ud83d\ude80 \ud800";
        return ledger;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("new golden output path");
        byte[] bytes=fixture().encode();
        Files.write(Path.of(args[0]),bytes,StandardOpenOption.CREATE_NEW);
        System.out.println(bytes.length+" bytes SHA-256 "+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
