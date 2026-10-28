package com.watchnc.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class PatrolStateTest {
    @Test public void freshnessRejectsOldAndFutureSamples() {
        assertTrue(PatrolState.fresh(100000, 115000));
        assertFalse(PatrolState.fresh(100000, 115001));
        assertFalse(PatrolState.fresh(100000, 94999));
        assertFalse(PatrolState.fresh(0, 1));
    }
    @Test public void samplesRecoverFromExpiredOrFutureWatermarks() {
        long now = 100000;
        assertFalse(PatrolState.acceptSample(now - 1, now, now));
        assertFalse(PatrolState.acceptSample(now + 5001, now, now));
        assertTrue(PatrolState.acceptSample(now - 1, now + 10000, now));
        assertTrue(PatrolState.acceptSample(now, now - 16000, now));
        assertFalse(PatrolState.acceptSample(now - 16000, 0, now));
    }
    @Test public void labelsPreserveSimultaneousColorsAndIgnoreOtherBits() {
        assertEquals("RYG", PatrolState.shortLabel(0xff));
        assertEquals("OFF", PatrolState.shortLabel(0x2f));
        assertEquals("?", PatrolState.shortLabel(-1));
    }
    @Test public void onlyKnownLampChangesCauseAlerts() {
        assertTrue(PatrolState.changedKnown(0x40, 0x10));
        assertTrue(PatrolState.changedKnown(0, 0x80));
        assertFalse(PatrolState.changedKnown(0x40, 0x41));
        assertFalse(PatrolState.changedKnown(-1, 0x40));
        assertFalse(PatrolState.changedKnown(0x40, -1));
    }
}
