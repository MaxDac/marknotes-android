package io.github.maxdac.marknotes.core.notes

/**
 * The key under which paths that differ only in case are the same path: Unicode simple case folding, one
 * character to one, so `ß` and `İ` stay as they are.
 *
 * The apps that sync notes compute the same key in the shared sync library (`foldCase`); both must agree, because
 * a rename is "case only" exactly when the keys are equal. This version has no dependency on that library and
 * folds through the JVM's simple case mappings (upper, then lower), which gives the same equivalence classes as the
 * `C` and `S` mappings of CaseFolding.txt except for the dotted and dotless I, kept apart below. The sync
 * library's own test vectors are repeated in `CaseFoldingTest`.
 */
fun foldCase(path: String): String {
    val folded = StringBuilder(path.length)
    var index = 0
    while (index < path.length) {
        val codePoint = path.codePointAt(index)
        index += Character.charCount(codePoint)
        val key = when (codePoint) {
            DOTTED_CAPITAL_I, DOTLESS_SMALL_I -> codePoint
            else -> Character.toLowerCase(Character.toUpperCase(codePoint))
        }
        folded.appendCodePoint(key)
    }
    return folded.toString()
}

private const val DOTTED_CAPITAL_I = 0x0130
private const val DOTLESS_SMALL_I = 0x0131
