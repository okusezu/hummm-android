package com.okusezu.hummm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VisStyleTest {
    @Test fun swipingWrapsBothWays() {
        assertEquals(VisStyle.MONO, VisStyle.CAVA.next(1))
        assertEquals(VisStyle.FIRE, VisStyle.CAVA.next(-1))
        assertEquals(VisStyle.CAVA, VisStyle.FIRE.next(1))
    }

    @Test fun unknownSavedStyleFallsBackToCava() {
        assertEquals(VisStyle.CAVA, VisStyle.named("lasers"))
        assertEquals(VisStyle.MATRIX, VisStyle.named("matrix"))
    }
}
