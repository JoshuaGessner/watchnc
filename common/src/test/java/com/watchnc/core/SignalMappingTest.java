package com.watchnc.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SignalMappingTest {
    @Test public void mapsDistinctBitsAndPolarity() {
        assertEquals(0x90, new SignalMapping(32, 0, 2, 7, false).normalize(5));
        assertEquals(0x90, new SignalMapping(32, 0, 2, 7, true).normalize(250));
        assertEquals(0, new SignalMapping(32, 7, 4, 6, false).normalize(0));
    }
    @Test public void rejectsBadMappings() {
        try { new SignalMapping(32, 1, 1, 2, false); fail(); } catch (IllegalArgumentException expected) { }
        try { new SignalMapping(65536, 1, 2, 3, false); fail(); } catch (IllegalArgumentException expected) { }
        try { new SignalMapping(32, -1, 2, 3, false); fail(); } catch (IllegalArgumentException expected) { }
    }
}
