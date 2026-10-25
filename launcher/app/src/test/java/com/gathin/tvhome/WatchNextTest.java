package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WatchNextTest {
    @Test public void aShortRowIsPaddedToAWholeLine() {
        // Three programmes under a six-app dock: three empty slots, so the apps start on the next line.
        assertEquals(3, WatchNext.padding(3, 6));
        assertEquals(5, WatchNext.padding(1, 6));
    }

    @Test public void progressIsTheShareWatched() {
        assertEquals(0.5f, WatchNext.progress(30 * 60_000L, 60 * 60_000L), 1e-6);
        assertEquals(1f, WatchNext.progress(70 * 60_000L, 60 * 60_000L), 1e-6);
        // A programme just started still shows a sliver.
        assertEquals(0.03f, WatchNext.progress(1_000L, 60 * 60_000L), 1e-6);
    }

    @Test public void noBarWithoutADurationOrBeforePlaybackStarted() {
        assertEquals(-1f, WatchNext.progress(30_000L, 0L), 1e-6);
        assertEquals(-1f, WatchNext.progress(0L, 60 * 60_000L), 1e-6);
    }

    @Test public void minutesLeftRoundUp() {
        assertEquals(23, WatchNext.minutesLeft(37 * 60_000L, 60 * 60_000L));
        assertEquals(1, WatchNext.minutesLeft(60 * 60_000L - 5_000L, 60 * 60_000L));
        assertEquals(-1, WatchNext.minutesLeft(60 * 60_000L, 60 * 60_000L));
        assertEquals(-1, WatchNext.minutesLeft(0L, 60 * 60_000L));
    }

    @Test public void yandexArtIsAskedFor1080p() {
        String base = "https://cdnrr.svc.cdn.yandex.net/get-ott/200035/2a0000016d012fbab01dfed1e281772e6e71/";
        assertEquals(base + "1920x1080", WatchNext.largeArt(base + "460x690"));
        assertEquals(base + "1920x1080", WatchNext.largeArt(base + "1920x1080"));
    }

    @Test public void otherArtIsLeftAsItIs() {
        String youtube = "https://i.ytimg.com/vi/L_PWNMQC9iE/hq720.jpg?sqp=x";
        assertEquals(youtube, WatchNext.largeArt(youtube));
        String yandexOrig = "https://cdnrr.svc.cdn.yandex.net/get-ott/200035/2a00/orig";
        assertEquals(yandexOrig, WatchNext.largeArt(yandexOrig));
        assertEquals("http://x.yandex.net/get-ott/1/2/460x690", WatchNext.largeArt("http://x.yandex.net/get-ott/1/2/460x690"));
    }

    @Test public void aFullOrEmptyRowGetsNoPadding() {
        assertEquals(0, WatchNext.padding(6, 6));
        assertEquals(0, WatchNext.padding(7, 6));
        assertEquals(0, WatchNext.padding(0, 6));
    }
}
