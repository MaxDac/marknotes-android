package io.github.maxdac.marknotes.core.notes

/**
 * The key under which paths that differ only in case are the same path: Unicode simple case folding, one
 * character to one, so `ß` and `İ` stay as they are.
 *
 * The apps that sync notes compute the same key in the shared sync library (`foldCase`); both must agree, because
 * a rename is "case only" exactly when the keys are equal. This version has no dependency on that library and
 * folds through the JVM's simple case mappings (upper, then lower), which gives the same equivalence classes as the
 * `C` and `S` mappings of CaseFolding.txt except for the dotted and dotless I, kept apart below, and the three
 * `S` mappings of Unicode 15.1 that older JVMs lack, added below. The keys themselves may differ from the library's
 * (it folds Cherokee to upper case): only the classes must agree. The sync library's own test vectors are repeated
 * in `CaseFoldingTest`.
 */
fun foldCase(path: String): String {
    val folded = StringBuilder(path.length)
    var index = 0
    while (index < path.length) {
        val codePoint = path.codePointAt(index)
        index += Character.charCount(codePoint)
        val key = when (codePoint) {
            DOTTED_CAPITAL_I, DOTLESS_SMALL_I -> codePoint

            // Simple foldings added in Unicode 15.1 that the JVM's upper-then-lower mapping lacks.
            GREEK_SMALL_IOTA_DIALYTIKA_PERISPOMENI -> GREEK_SMALL_IOTA_DIALYTIKA_TONOS

            GREEK_SMALL_UPSILON_DIALYTIKA_PERISPOMENI -> GREEK_SMALL_UPSILON_DIALYTIKA_TONOS

            LONG_S_T_LIGATURE -> ST_LIGATURE

            else -> Character.toLowerCase(Character.toUpperCase(codePoint))
        }
        folded.appendCodePoint(key)
    }
    return folded.toString()
}

private const val DOTTED_CAPITAL_I = 0x0130
private const val DOTLESS_SMALL_I = 0x0131
private const val GREEK_SMALL_IOTA_DIALYTIKA_PERISPOMENI = 0x1FD3
private const val GREEK_SMALL_IOTA_DIALYTIKA_TONOS = 0x0390
private const val GREEK_SMALL_UPSILON_DIALYTIKA_PERISPOMENI = 0x1FE3
private const val GREEK_SMALL_UPSILON_DIALYTIKA_TONOS = 0x03B0
private const val LONG_S_T_LIGATURE = 0xFB05
private const val ST_LIGATURE = 0xFB06
