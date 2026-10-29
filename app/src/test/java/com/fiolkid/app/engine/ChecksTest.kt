package com.fiolkid.app.engine

import com.fiolkid.app.Service
import org.junit.Assert.assertEquals
import org.junit.Test

/** Проверки на компьютере напрямую (без byedpi): здесь ничего не заблокировано — всё должно пройти, и gateway Discord тоже. */
class ChecksTest {
    @Test
    fun allOpenDirectly() {
        val result = Checks.run(null)
        assertEquals(result.problems.toString(), emptyList<String>(), result.problems.values.flatten())
        assertEquals(5, result.total)
        assertEquals(setOf(Service.YouTube, Service.Discord), result.problems.keys)
    }
}
