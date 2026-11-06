package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameUsabilityTest {
    @Test
    fun coveredOrBlankViewIsNotSent() {
        assertFalse(VisualMemoryFrameStore.isUsable(meanLuma = 12.0, stdLuma = 4.0))   // lens covered
        assertFalse(VisualMemoryFrameStore.isUsable(meanLuma = 90.0, stdLuma = 3.0))   // flat wall, nothing to see
        assertTrue(VisualMemoryFrameStore.isUsable(meanLuma = 140.0, stdLuma = 45.0))  // keyboard on a desk
    }
}
