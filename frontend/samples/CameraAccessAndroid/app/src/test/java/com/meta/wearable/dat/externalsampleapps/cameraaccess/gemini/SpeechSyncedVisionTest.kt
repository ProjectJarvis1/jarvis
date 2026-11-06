package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechSyncedVisionTest {
    private var frameAt = 0L
    private val sent = mutableListOf<Long>()
    private val vision = SpeechSyncedVision(
        latestFrame = { "frame$frameAt" to frameAt },
        send = { sent += it.removePrefix("frame").toLong() },
    )

    @Test
    fun sendsAtSpeechStartThenThrottlesThenClosesOnSilence() {
        frameAt = 1_000; vision.onAudio(true, 1_000)          // start: send
        frameAt = 1_300; vision.onAudio(true, 1_300)          // < 1s since last: skip
        frameAt = 2_050; vision.onAudio(true, 2_050)          // interval passed: send
        frameAt = 2_300; vision.onAudio(false, 2_300)         // short breath: still speaking
        frameAt = 2_600; vision.onAudio(false, 2_600)         // 550ms silence: closing frame
        assertEquals(listOf(1_000L, 2_050L, 2_600L), sent)
    }

    @Test
    fun neverSendsStaleOrRepeatedFrames() {
        frameAt = 0; vision.onAudio(true, 5_000)              // camera stopped long ago: nothing
        assertEquals(emptyList<Long>(), sent)
        frameAt = 5_000; vision.onAudio(false, 5_500)         // closing frame of that utterance
        vision.onAudio(true, 6_000)                           // same frame again: not resent
        assertEquals(listOf(5_000L), sent)
    }
}
