package com.watchnc.core;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateGateTest {
    @Test public void sendsChangesImmediatelyAndUnchangedDataOnHeartbeat() {
        UpdateGate gate = new UpdateGate(5000);
        assertEquals(1, gate.begin(Arrays.asList(1, "A"), 1000));
        assertEquals(0, gate.begin(Arrays.asList(1, "A"), 5999));
        assertEquals(2, gate.begin(Arrays.asList(1, "A"), 6000));
        assertEquals(3, gate.begin(Arrays.asList(2, "A"), 6001));
        assertEquals(4, gate.begin(Arrays.asList(2, "B"), 6002));
    }
    @Test public void retriesFailuresWithoutLettingOldFailuresUndoNewSends() {
        UpdateGate gate = new UpdateGate(5000);
        long first = gate.begin(Arrays.asList("A"), 0);
        long second = gate.begin(Arrays.asList("B"), 1);
        gate.failed(first);
        assertEquals(0, gate.begin(Arrays.asList("B"), 2));
        gate.failed(second);
        assertTrue(gate.begin(Arrays.asList("B"), 3) > 0);
    }
}
