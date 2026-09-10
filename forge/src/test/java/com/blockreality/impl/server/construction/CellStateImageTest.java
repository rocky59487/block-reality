package com.blockreality.impl.server.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class CellStateImageTest {
    // Registry-backed round trips run in the installed Forge harness. Bootstrap.bootStrap in an
    // untransformed JUnit JVM cannot initialize Forge's NetworkEvent listener list.
    @Test void canonicalCellKeysHaveExactBoundsAndNoAlternativeIntegerSpellings() throws Exception {
        for (BlockPos pos : new BlockPos[]{new BlockPos(-30000000,-2048,-30000000),new BlockPos(29999999,2047,29999999),BlockPos.ZERO})
            assertEquals(pos,CellStateImage.position(CellStateImage.key(pos)));
        for (String key : new String[]{"cell/30000000/0/0","cell/-30000001/0/0","cell/0/2048/0","cell/0/-2049/0","cell/0/0/30000000",
                "cell/+1/0/0","cell/01/0/0","cell/-0/0/0","cell/0/0/0/","cell/0//0","other/0/0/0","cell/2147483648/0/0"})
            assertThrows(IOException.class,()->CellStateImage.position(key));
    }
}
