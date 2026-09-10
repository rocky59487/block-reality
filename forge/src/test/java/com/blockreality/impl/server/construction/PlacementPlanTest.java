package com.blockreality.impl.server.construction;

import com.blockreality.core.transaction.ConstructionTransaction.Value;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlacementPlanTest {
    private static final Value A=Value.of(new byte[]{1}),B=Value.of(new byte[]{2}),C=Value.of(new byte[]{3}),D=Value.missing();
    private static PlacementPlan plan(boolean creative) {
        return new PlacementPlan(new UUID(1,2),new UUID(3,4),"minecraft:overworld",new BlockPos(1,80,2),new BlockPos(1,81,2),
                Direction.UP,new Vec3(1.5,81,2.5),InteractionHand.MAIN_HAND,2,"blockreality:steel_beam",0,creative,
                PlacementPlan.digest(A),PlacementPlan.digest(B),PlacementPlan.digest(C),PlacementPlan.digest(creative?C:D));
    }
    @Test void exactPlanPreservesBindingAndVerifiesEveryParticipant() throws Exception {
        PlacementPlan before=plan(false);assertEquals(before,PlacementPlan.decode(before.image()));
        before.verifyImages(A,B,C,D);assertThrows(IOException.class,()->before.verifyImages(A,B,C,Value.of(new byte[0])));
        assertThrows(IOException.class,()->before.verifyImages(B,A,C,D));
        assertNotEquals(PlacementPlan.digest(D),PlacementPlan.digest(Value.of(new byte[0])));
        assertNotEquals(before.hash(),plan(true).hash());
    }
    @Test void malformedExtraMissingAndUnknownSchemaCannotDefault() throws Exception {
        for(int fault=0;fault<6;fault++) {
            CompoundTag tag=CanonicalNbt.decode(plan(false).image().bytes(),PlacementPlan.MAX_BYTES);
            switch(fault){case 0->tag.putInt("format",2);case 1->tag.putString("extra","discarded");case 2->tag.remove("actor");
                case 3->tag.putByte("axis",(byte)0);case 4->tag.putIntArray("target",new int[]{1,80});case 5->tag.putInt("face",99);}
            Value image=Value.of(CanonicalNbt.encode(tag,PlacementPlan.MAX_BYTES));assertThrows(IOException.class,()->PlacementPlan.decode(image));
        }
        assertThrows(IOException.class,()->PlacementPlan.decode(Value.missing()));
    }
    @Test void targetAndSlotBoundsAreIndependentOfNBTDefaults() {
        PlacementPlan p=plan(false);
        assertThrows(IllegalArgumentException.class,()->new PlacementPlan(p.offer(),p.actor(),p.dimension(),p.clicked(),p.target(),p.face(),
                new Vec3(Double.NaN,81,2.5),p.hand(),p.slot(),p.product(),p.axis(),p.creative(),p.beforeCell(),p.afterCell(),p.beforeItem(),p.afterItem()));
        assertThrows(IllegalArgumentException.class,()->new PlacementPlan(p.offer(),p.actor(),p.dimension(),p.clicked(),new BlockPos(30000000,81,2),p.face(),
                p.hit(),p.hand(),p.slot(),p.product(),p.axis(),p.creative(),p.beforeCell(),p.afterCell(),p.beforeItem(),p.afterItem()));
        assertThrows(IllegalArgumentException.class,()->new PlacementPlan(p.offer(),p.actor(),p.dimension(),p.clicked(),p.target(),p.face(),
                p.hit(),InteractionHand.OFF_HAND,2,p.product(),p.axis(),p.creative(),p.beforeCell(),p.afterCell(),p.beforeItem(),p.afterItem()));
    }
}
