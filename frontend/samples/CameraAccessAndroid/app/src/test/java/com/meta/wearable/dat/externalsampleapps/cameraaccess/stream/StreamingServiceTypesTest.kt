package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingServiceTypesTest {
    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    @Test
    fun longRunningServiceNeverAsksForTheTimeLimitedType() {
        listOf(true, false).forEach { glasses ->
            listOf(true, false).forEach { microphone ->
                StreamingService.candidateTypes(glasses, microphone).forEach { assertTrue(it and dataSync == 0) }
            }
        }
    }

    @Test
    fun typesFollowTheBuildAndMicrophone() {
        assertEquals(listOf(device or mic, mic, device), StreamingService.candidateTypes(glasses = true, microphone = true))
        assertEquals(listOf(device), StreamingService.candidateTypes(glasses = true, microphone = false))
        assertEquals(listOf(mic), StreamingService.candidateTypes(glasses = false, microphone = true))
        // Phone build without the microphone has nothing to keep running, so the service is not started.
        assertEquals(emptyList<Int>(), StreamingService.candidateTypes(glasses = false, microphone = false))
    }
}
