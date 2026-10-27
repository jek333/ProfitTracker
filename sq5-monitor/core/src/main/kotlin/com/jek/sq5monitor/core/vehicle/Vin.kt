package com.jek.sq5monitor.core.vehicle

/** What can be read straight out of a 17 character VIN. */
data class VinInfo(
    val vin: String,
    val manufacturer: String?,
    val model: String?,
    val modelYear: Int?,
    /** False when position 9 is not a valid check digit (mandatory in North America, optional in Europe). */
    val checkDigitValid: Boolean,
)

object Vin {
    private val TRANSLITERATION: Map<Char, Int> = buildMap {
        "ABCDEFGH".forEachIndexed { i, c -> put(c, i + 1) }
        "JKLMN".forEachIndexed { i, c -> put(c, i + 1) }
        put('P', 7); put('R', 9)
        "STUVWXYZ".forEachIndexed { i, c -> put(c, i + 2) }
        ('0'..'9').forEach { put(it, it - '0') }
    }
    private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    /** Model year codes repeat every 30 years; 2010-2039 covers any car this app will see. */
    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    private val MANUFACTURERS = mapOf(
        "WAU" to "Audi AG",
        "WA1" to "Audi AG (SUV)",
        "WUA" to "Audi Sport GmbH",
        "TRU" to "Audi Hungaria",
        "WVW" to "Volkswagen",
        "WVG" to "Volkswagen (SUV)",
        "3VW" to "Volkswagen Mexico",
        "WP0" to "Porsche",
        "WP1" to "Porsche (SUV)",
    )

    /** Audi encodes the model family in VIN positions 7-8. */
    private val AUDI_MODELS = mapOf(
        "FY" to "Q5 / SQ5 (FY, 2nd generation)",
        "8R" to "Q5 / SQ5 (8R, 1st generation)",
        "F4" to "Q8 / e-tron",
        "4M" to "Q7 / Q8 (4M)",
        "GN" to "e-tron",
        "F3" to "Q3 (F3)",
        "8W" to "A4 / S4 (B9)",
        "F5" to "A5 / S5 (B9)",
        "4K" to "A6 / A7 (C8)",
        "8Y" to "A3 / S3 (8Y)",
        "8V" to "A3 / S3 (8V)",
    )

    fun isValidFormat(vin: String): Boolean =
        vin.length == 17 && vin.all { it in TRANSLITERATION } && vin.none { it in "IOQ" }

    /** ISO 3779 / 49 CFR 565 check digit (position 9). */
    fun checkDigit(vin: String): Char? {
        if (!isValidFormat(vin)) return null
        val sum = vin.indices.sumOf { TRANSLITERATION.getValue(vin[it]) * WEIGHTS[it] }
        val remainder = sum % 11
        return if (remainder == 10) 'X' else '0' + remainder
    }

    fun decode(raw: String): VinInfo {
        val vin = raw.trim().uppercase()
        val wmi = vin.take(3)
        val manufacturer = MANUFACTURERS[wmi]
        val isAudi = wmi in setOf("WAU", "WA1", "WUA", "TRU")
        val model = if (isAudi && vin.length >= 8) AUDI_MODELS[vin.substring(6, 8)] else null
        val yearIndex = vin.getOrNull(9)?.let { YEAR_CODES.indexOf(it) } ?: -1
        val modelYear = if (yearIndex >= 0) 2010 + yearIndex else null
        return VinInfo(
            vin = vin,
            manufacturer = manufacturer,
            model = model,
            modelYear = modelYear,
            checkDigitValid = checkDigit(vin) == vin.getOrNull(8),
        )
    }
}
