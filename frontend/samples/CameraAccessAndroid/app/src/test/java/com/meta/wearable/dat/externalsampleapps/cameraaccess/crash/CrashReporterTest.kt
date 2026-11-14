package com.meta.wearable.dat.externalsampleapps.cameraaccess.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {
    @Test
    fun stackKeepsClassesAndLinesButNeverTheMessage() {
        val error = IllegalStateException("김민수 님 견적서 메모", RuntimeException("대화 내용"))
        val stack = CrashReporter.stackOf(error)
        assertTrue(stack.startsWith("java.lang.IllegalStateException\n"))
        assertTrue(stack.contains("Caused by: java.lang.RuntimeException"))
        assertTrue(stack.contains("\tat "))
        assertFalse(stack.contains("김민수"))
        assertFalse(stack.contains("대화"))
    }

    @Test
    fun stackIsCappedAndCyclesStop() {
        val a = RuntimeException()
        val b = RuntimeException(a)
        a.initCause(b)
        a.stackTrace = Array(500) { StackTraceElement("x.Y" + "z".repeat(60), "m", "F.kt", it) }
        val stack = CrashReporter.stackOf(a)
        assertTrue(stack.length <= CrashReporter.MAX_STACK)
        assertEquals(2, Regex("RuntimeException").findAll(stack).count())
    }

    @Test
    fun reportsAreDroppedOnlyWhenTakenOrRefusedForGood() {
        assertTrue(CrashReporter.shouldDrop(202))
        assertTrue(CrashReporter.shouldDrop(422))
        assertFalse(CrashReporter.shouldDrop(401))
        assertFalse(CrashReporter.shouldDrop(429))
        assertFalse(CrashReporter.shouldDrop(503))
    }
}
