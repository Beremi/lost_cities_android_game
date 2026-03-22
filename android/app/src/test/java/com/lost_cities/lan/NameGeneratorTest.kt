package com.lost_cities.lan

import com.lost_cities.lan.data.NameGenerator
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class NameGeneratorTest {
    @Test
    fun generatedNameAlwaysEndsWithDigits() {
        repeat(20) {
            val name = NameGenerator.generate(Random(100 + it))
            val trailing = name.takeLastWhile { c -> c.isDigit() }
            assertTrue(trailing.length >= 3)
        }
    }

    @Test
    fun ensureNumericSuffixAppendsWhenMissing() {
        val out = NameGenerator.ensureNumericSuffix("Player", Random(42))
        assertTrue(out.startsWith("Player"))
        assertTrue(out.last().isDigit())
    }
}
