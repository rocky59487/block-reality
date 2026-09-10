package com.blockreality.core.transaction;

import com.blockreality.api.geom.BlockKey;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.blockreality.core.transaction.ConstructionTransaction.*;
import static com.blockreality.core.transaction.TransactionFixtures.*;
import static com.blockreality.core.transaction.ManufacturedRegistryTest.*;

class MetadataCodecTest {
    static ManufacturedPiece piece() {
        return new ManufacturedPiece(new UUID(44,44),request(1,0).id(),ACTOR,OVERWORLD,STEEL,1,1,ManufacturedPiece.Status.INTACT,run(0,2).cells());
    }
    @Test void allRecordStatesRoundTripCanonicallyAndNeverExposeCellArrays() {
        var original=piece();
        for (var p : List.of(original,original.edited(Set.of(new BlockKey(0,80,0)),false),original.edited(Set.of(),true))) {
            Value bytes=MetadataCodec.piece(p);assertEquals(p,MetadataCodec.piece(bytes));
            assertEquals(bytes,MetadataCodec.piece(MetadataCodec.piece(bytes)));
            byte[] copy=bytes.bytes();Arrays.fill(copy,(byte)0);assertEquals(p,MetadataCodec.piece(bytes));
        }
        var mutable=new ArrayList<>(run(0,2).cells());var plan=new ManufacturedPiece.Plan(STEEL,mutable);mutable.clear();
        assertEquals(2,plan.cells().size());assertThrows(UnsupportedOperationException.class,()->plan.cells().clear());
    }
    @Test void everyTruncationTrailingDataSchemaAndUnsuitableFieldIsRejected() {
        byte[] valid=MetadataCodec.piece(piece()).bytes();
        for (int n=0;n<valid.length;n++) {
            byte[] shortened=Arrays.copyOf(valid,n);assertThrows(IllegalArgumentException.class,()->MetadataCodec.piece(Value.of(shortened)),"length "+n);
        }
        assertThrows(IllegalArgumentException.class,()->MetadataCodec.piece(Value.of(Arrays.copyOf(valid,valid.length+1))));
        for (int offset : List.of(0,4,56)) {
            byte[] bad=valid.clone();ByteBuffer.wrap(bad).putInt(offset,Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class,()->MetadataCodec.piece(Value.of(bad)),"offset "+offset);
        }
        int countOffset=valid.length-24-4;
        for (int count : List.of(-1,4097,Integer.MAX_VALUE)) {
            byte[] bad=valid.clone();ByteBuffer.wrap(bad).putInt(countOffset,count);
            assertThrows(IllegalArgumentException.class,()->MetadataCodec.piece(Value.of(bad)));
        }
        byte[] duplicate=valid.clone();System.arraycopy(duplicate,duplicate.length-24,duplicate,duplicate.length-12,12);
        assertThrows(IllegalArgumentException.class,()->MetadataCodec.piece(Value.of(duplicate)));
        byte[] reversed=valid.clone();System.arraycopy(valid,valid.length-12,reversed,reversed.length-24,12);System.arraycopy(valid,valid.length-24,reversed,reversed.length-12,12);
        assertThrows(IllegalArgumentException.class,()->MetadataCodec.piece(Value.of(reversed)));
    }
    @Test void descriptorsBindOperationCreativeModeDimensionAndOriginalBuild() {
        for (var descriptor : List.of(new MetadataCodec.Descriptor(MetadataCodec.Operation.BUILD,OVERWORLD,false,null),
                new MetadataCodec.Descriptor(MetadataCodec.Operation.BUILD,OVERWORLD,true,null),
                new MetadataCodec.Descriptor(MetadataCodec.Operation.EDIT,NETHER,false,null),
                new MetadataCodec.Descriptor(MetadataCodec.Operation.UNDO,OVERWORLD,true,request(1,0).id())))
            assertEquals(descriptor,MetadataCodec.descriptor(MetadataCodec.descriptor(descriptor)));
        assertThrows(IllegalArgumentException.class,()->new MetadataCodec.Descriptor(MetadataCodec.Operation.UNDO,OVERWORLD,false,null));
        assertThrows(IllegalArgumentException.class,()->new MetadataCodec.Descriptor(MetadataCodec.Operation.BUILD,OVERWORLD,false,request(1,0).id()));
        assertThrows(IllegalArgumentException.class,()->new MetadataCodec.Descriptor(MetadataCodec.Operation.EDIT,OVERWORLD,true,null));
        byte[] valid=MetadataCodec.descriptor(new MetadataCodec.Descriptor(MetadataCodec.Operation.BUILD,OVERWORLD,false,null)).bytes();
        for (int offset : List.of(8,valid.length-1,valid.length-2)) {
            byte[] bad=valid.clone();bad[offset]=(byte)99;
            assertThrows(IllegalArgumentException.class,()->MetadataCodec.descriptor(Value.of(bad)));
        }
        assertThrows(IllegalArgumentException.class,()->MetadataCodec.descriptor(Value.of(Arrays.copyOf(valid,valid.length+1))));
    }
    @Test void numericAndDimensionEncodingsAreBoundedAndUnambiguous() {
        for (long n : List.of(0L,1L,Long.MAX_VALUE)) assertEquals(n,MetadataCodec.number(MetadataCodec.number(n)));
        assertThrows(IllegalArgumentException.class,()->MetadataCodec.number(-1));
        assertThrows(IllegalArgumentException.class,()->MetadataCodec.number(Value.of(new byte[9])));
        assertThrows(IllegalArgumentException.class,()->MetadataCodec.number(Value.missing()));
        assertNotEquals(MetadataCodec.revisionKey(OVERWORLD),MetadataCodec.revisionKey(NETHER));
        for (String d : List.of("minecraft",":",OVERWORLD+" ","Minecraft:overworld","a:"+"x".repeat(255),"a:世界"))
            assertThrows(IllegalArgumentException.class,()->MetadataCodec.revisionKey(d));
    }
}
