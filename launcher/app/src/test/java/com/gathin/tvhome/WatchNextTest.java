package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WatchNextTest {
    @Test public void aShortRowIsPaddedToAWholeLine() {
        // Three programmes under a six-app dock: three empty slots, so the apps start on the next line.
        assertEquals(3, WatchNext.padding(3, 6));
        assertEquals(5, WatchNext.padding(1, 6));
    }

    @Test public void aFullOrEmptyRowGetsNoPadding() {
        assertEquals(0, WatchNext.padding(6, 6));
        assertEquals(0, WatchNext.padding(7, 6));
        assertEquals(0, WatchNext.padding(0, 6));
    }
}
