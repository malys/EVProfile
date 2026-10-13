package com.evsuite.profile.profile

import org.junit.Assert.assertEquals
import org.junit.Test

/** A read-back sits inside a write sequence: a failed read must become text, never a throw. */
class ReadBackTest {

    @Test fun valueNullAndExceptionAreAllText() {
        assertEquals("2", ReadBack.show { 2 })
        assertEquals("unreadable", ReadBack.show { null })
        assertEquals("exc(IllegalStateException)", ReadBack.show { throw IllegalStateException("binder") })
    }

    @Test fun lineNeverThrowsOffDevice() {
        // No car and no CarPropertyManager in a JVM test: every reader fails, nothing escapes.
        ReadBack.regen("test", "before")
        ReadBack.custom("test", "before")
        ReadBack.climate("before")
    }
}
