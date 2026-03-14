package com.carcassonne.lan.data

import kotlin.random.Random

object NameGenerator {
    private val adjectives = listOf(
        "Amber",
        "Brisk",
        "Clever",
        "Daring",
        "Ember",
        "Frost",
        "Golden",
        "Harbor",
        "Iron",
        "Jade",
    )

    private val nouns = listOf(
        "Builder",
        "Raven",
        "Ranger",
        "Falcon",
        "Keeper",
        "Mason",
        "Scout",
        "Weaver",
        "Warden",
        "Voyager",
    )

    fun generate(random: Random = Random.Default): String {
        val left = adjectives[random.nextInt(adjectives.size)]
        val right = nouns[random.nextInt(nouns.size)]
        val suffix = random.nextInt(1000, 9999)
        return "$left$right$suffix"
    }

    fun ensureNumericSuffix(rawName: String, random: Random = Random.Default): String {
        val cleaned = rawName.trim().replace(Regex("\\s+"), " ")
        val limited = cleaned.take(24)
        if (limited.isBlank()) return generate(random)
        return if (limited.last().isDigit()) {
            val trailingDigits = limited.takeLastWhile { it.isDigit() }
            if (trailingDigits.length >= 3) limited else "$limited${random.nextInt(100, 999)}"
        } else {
            "$limited${random.nextInt(1000, 9999)}"
        }
    }
}
