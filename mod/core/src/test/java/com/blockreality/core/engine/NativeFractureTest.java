package com.blockreality.core.engine;

import com.blockreality.core.bsi.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeFractureTest {
    static Path library() {
        String path=System.getProperty("br.engine","");
        Assumptions.assumeTrue(!path.isEmpty(),"Native fracture requires explicit br.engine; absent is SKIP");
        assertTrue(Files.isRegularFile(Path.of(path))); return Path.of(path);
    }
    @TestFactory Stream<DynamicTest> oracleScenariosThroughProductionSession() {
        Path lib=library();
        return FractureCases.all().stream().map(c -> DynamicTest.dynamicTest(c.name(),() -> {
            try (var engine=c.open(lib)) {
                assertTrue(engine.declareIdentifiedWorld(c.world()));
                var receipt=engine.prepareFracture(c.request(),c.options());
                assertEquals(c.world(),engine.identifiedWorld(),"prepare must not publish");
                if (c.refused()) { assertNull(receipt); assertEquals(InProcessEngine.Status.READY,engine.status()); return; }
                assertNotNull(receipt,() -> String.valueOf(engine.disabledReason()));
                var golden=c.receipt(); byte[] frame=receipt.frame();
                assertArrayEquals(c.payload(),BsiFrame.decode(frame,frame.length).payload(),"full seven-section native oracle bytes");
                assertEquals(golden.cells(),receipt.cells()); assertEquals(golden.fragments(),receipt.fragments());
                assertEquals(golden.totals(),receipt.totals()); assertEquals(golden.events(),receipt.events());
                assertEquals(golden.mechanism(),receipt.mechanism()); assertEquals(golden.flags(),receipt.flags());
                assertEquals(golden.steps(),receipt.steps()); assertEquals(golden.remainingBlocks(),receipt.remainingBlocks());
                var retry=engine.prepareFracture(c.request(),c.options());
                assertNotNull(retry); assertEquals(receipt.token(),retry.token());
                assertEquals(BsiFracture.Finish.COMMITTED,engine.finishFracture(retry,true));
                assertEquals(c.world().remaining(golden),engine.identifiedWorld());
                assertEquals(BsiFracture.Finish.REPLAYED,engine.finishFracture(retry,true));
                assertEquals(BsiFracture.Finish.DISCARDED,engine.finishFracture(retry,false));
                assertEquals(c.world().remaining(golden),engine.identifiedWorld(),"discard cannot undo committed material removal");
            }
        }));
    }
    @Test void rejectedWorldAndOptionsPreserveLiveCandidateAndSource() {
        var c=FractureCases.named("L"); try (var e=c.open(library())) {
            assertTrue(e.declareIdentifiedWorld(c.world())); var first=e.prepareFracture(c.request(),c.options()); assertNotNull(first);
            var incomplete=new BsiFracture.World(c.world().stamp(),c.world().artifactNamespace(),c.world().blocks(),List.of());
            assertFalse(e.declareIdentifiedWorld(incomplete)); assertEquals(c.world(),e.identifiedWorld());
            var changed=new BsiFracture.Options(0,-1,0,16,4);
            assertNull(e.prepareFracture(c.request(),changed)); assertEquals(c.world(),e.identifiedWorld());
            assertEquals(BsiFracture.Finish.COMMITTED,e.finishFracture(first,true));
        }
    }
    @Test void foreignSessionAndStoredTokensCannotCommit() {
        var c=FractureCases.named("L");
        try (var a=c.open(library()); var b=c.open(library())) {
            assertTrue(a.declareIdentifiedWorld(c.world())); assertTrue(b.declareIdentifiedWorld(c.world()));
            var one=a.prepareFracture(c.request(),c.options()); var two=b.prepareFracture(c.request(),c.options());
            assertNotNull(one); assertNotNull(two); assertNotEquals(one.token().context(),two.token().context());
            assertNull(b.finishFracture(one,true)); assertNull(b.finishFracture(c.receipt(),true));
            assertEquals(c.world(),b.identifiedWorld()); assertEquals(BsiFracture.Finish.DISCARDED,a.finishFracture(one,false));
            assertNull(a.finishFracture(one,true)); assertEquals(c.world(),a.identifiedWorld());
        }
    }
}
