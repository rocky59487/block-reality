package com.blockreality.impl.server;

import com.blockreality.api.geom.BlockKey;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputCoverageTest {
    @Test void unreadableStructureAndGroundNeverDispatch() {
        for (BlockKey missing : new BlockKey[]{new BlockKey(16, 200, 0), new BlockKey(15, 199, 0)}) {
            var coverage = new InputCoverage(); var calls = new AtomicInteger();
            coverage.missing(missing); coverage.missing(missing);
            assertFalse(coverage.dispatch(calls::incrementAndGet)); assertEquals(0, calls.get());
            assertEquals(1, coverage.missingCount()); assertTrue(coverage.detail().contains("1 cell"));
        }
    }
    @Test void fullyObservedDomainDispatchesOnce() {
        var coverage = new InputCoverage(); var calls = new AtomicInteger();
        assertTrue(coverage.dispatch(calls::incrementAndGet)); assertEquals(1, calls.get());
    }
}
