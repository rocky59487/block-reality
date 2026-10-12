package com.blockreality.core.finite;

import com.blockreality.api.geom.BlockKey;
import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiRecords;
import com.blockreality.core.transaction.ConstructionTransaction;
import com.blockreality.core.world.ConstructionDeclaration;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared finite test images: a one-block structural + one support world with valid vocab. */
final class FiniteFixtures {

    static final UUID JOURNAL = new UUID(7, 7), ACTOR = new UUID(8, 8), SESSION = new UUID(9, 9);
    static final String DIMENSION = "minecraft:overworld";

    static final String VOCAB_REQUEST = """
            {"version":1,
             "materials":[
               {"name":"steel","role":"member","model":"isotropic","E":2.0e11,"nu":0.3,"rho":7850,
                "allow":{"sigmaC":2.5e8,"sigmaT":2.5e8,"tau":1.45e8},"defaultSection":"steel_rect_200x400"},
               {"name":"ground_rigid","role":"support","supportKind":"fixAll"}],
             "sections":[{"name":"steel_rect_200x400","kind":"rect","p":[0.2,0.4]}]}""";

    static String vocabReply(String id, long revision) {
        return "{\"bsi\":1,\"kind\":\"response\",\"id\":\"" + id + "\",\"method\":\"bsi.vocab.declare\","
                + "\"revision\":" + revision + ",\"status\":\"ok\",\"version\":1,"
                + "\"materials\":[{\"id\":0,\"name\":\"steel\"},{\"id\":1,\"name\":\"ground_rigid\"}],"
                + "\"sections\":[{\"id\":0,\"name\":\"steel_rect_200x400\"}]}";
    }

    static final BsiRecords.Block STEEL =
            new BsiRecords.Block(1, 64, 1, 0, 0, 1, 0, 0, 1.0, 1.0);
    static final BsiRecords.Block GROUND =
            new BsiRecords.Block(0, 63, 0, 1, -1, 0, 0, 0, 1.0, 1.0);

    static byte[] options176(UUID bsiDomain, long revision) {
        var b = ByteBuffer.allocate(176).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(176).putInt(1);                          // struct_size, flags bit0 selfWeight
        b.putLong(bsiDomain.getMostSignificantBits()).putLong(bsiDomain.getLeastSignificantBits());
        b.putLong(revision);
        b.putDouble(1.0);                                 // loadFactor
        b.putDouble(0).putDouble(-9.81).putDouble(0);     // gravity
        b.putDouble(1e-4).putDouble(1e-3).putDouble(1e-3);// rel/force/moment tolerance
        b.putDouble(1.0).putDouble(1e-6).putDouble(1.0);  // initial/min/max step
        b.putDouble(1e-8).putDouble(1e-4);                // linear/path tolerance
        b.putInt(1 << 20).putInt(0);                      // budgetDof, numThreads (host)
        b.putInt(50).putInt(4).putInt(8).putInt(25).putInt(1); // iterations/attempts/backtracks/linear/restart
        b.putInt(4096).putInt(32).putInt(4).putInt(8);    // point/fiber/radial/angular budgets
        b.putInt(0);                                      // trailing pad
        return b.array();
    }

    static FiniteSource source(UUID bsiDomain, long revision) {
        return FiniteSource.build(VOCAB_REQUEST, vocabReply("r1", 0),
                List.of(GROUND, STEEL),                        // deliberately unsorted
                List.of(new BsiFracture.Owner(new BlockKey(1, 64, 1), 1)),
                Set.of(1),                                     // material 1 is the support
                new byte[152],                                 // one material record
                new byte[0], new byte[0],
                new byte[0], new byte[0], new byte[0],
                options176(bsiDomain, revision));
    }

    static FiniteDomainId domainId(String dimension) {
        return new FiniteDomainId(JOURNAL, dimension, new UUID(11, 11), new UUID(12, 12));
    }

    static ConstructionDeclaration declaration() {
        return new ConstructionDeclaration("steel", "steel_rect_200x400", 1);
    }

    static ConstructionTransaction.Request request(long key, String planHash) {
        return new ConstructionTransaction.Request(new UUID(20, key), ACTOR, SESSION, JOURNAL, 0, planHash);
    }

    static ConstructionTransaction.Request request(long key, FiniteCodec.Operation op) {
        return request(key, FiniteRegistry.planHash(op));
    }
}
