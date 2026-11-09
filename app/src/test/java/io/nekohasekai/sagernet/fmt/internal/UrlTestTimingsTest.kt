package io.nekohasekai.sagernet.fmt.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlTestTimingsTest {
    @Test
    fun parsesGoDurations() {
        assertEquals(600_000L, goDurationMillis("10m0s"))
        assertEquals(5_400_000L, goDurationMillis("1h30m"))
        assertEquals(90_000L, goDurationMillis("90s"))
        assertEquals(500L, goDurationMillis("500ms"))
        assertEquals(180_000L, goDurationMillis("180"))
        assertNull(goDurationMillis("soon"))
        assertNull(goDurationMillis("10m x"))
    }

    @Test
    fun idleTimeoutIsStretchedToTheInterval() {
        // the subscription's 10m interval against the bean's 3m default
        assertEquals("10m0s", urlTestIdleTimeoutFor("10m0s", "3m"))
        assertEquals("30m", urlTestIdleTimeoutFor("10m0s", "30m"))
        assertEquals("3m", urlTestIdleTimeoutFor("3m", "3m"))
        // unparsable idle: fall back to the interval; unparsable interval: keep idle
        assertEquals("10m", urlTestIdleTimeoutFor("10m", "forever"))
        assertEquals("3m", urlTestIdleTimeoutFor("whenever", "3m"))
    }
}
