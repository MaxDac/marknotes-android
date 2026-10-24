package io.github.maxdac.marknotes.core.diagnostics

import java.net.URLEncoder

data class IssueDraft(val body: String, val url: String, val omitted: Int)

object DiagnosticReport {
    const val MAX_URL_LENGTH = 2_000
    private val repositories = setOf("MaxDac/marknotes-android", "MaxDac/MarkNotes")

    fun localText(events: List<DiagnosticEvent>): String =
        "MarkNotes sanitized error logs\nTimestamp: Unix milliseconds; operation; failure category\n" +
            if (events.isEmpty()) "No errors recorded.\n" else events.joinToString("\n", postfix = "\n") { it.line() }

    /** Numeric package/OS metadata only; no arbitrary version names, model names, or identifiers. */
    fun issueDraft(
        repository: String,
        events: List<DiagnosticEvent>,
        versionCode: Long,
        apiLevel: Int
    ): IssueDraft {
        require(repository in repositories)
        val base = "https://github.com/$repository/issues/new?title=Problem%20report&body="
        var included = emptyList<DiagnosticEvent>()
        fun body(): String {
            val omitted = events.size - included.size
            return "## What happened?\nPlease describe the problem and steps to reproduce.\n\n" +
                "## Environment\nVersion code: $versionCode; Android API: $apiLevel\n\n" +
                "## Sanitized error logs\n" +
                "$omitted event(s) omitted. Full sanitized logs are available through local export.\n" +
                "```text\n" +
                (
                    if (included.isEmpty()) {
                        "No events included.\n"
                    } else {
                        included.joinToString("\n", postfix = "\n") {
                            it.line()
                        }
                    }
                    ) +
                "```\n"
        }
        for (event in events.asReversed()) {
            val previous = included
            included = listOf(event) + included
            if ((base + encode(body())).length > MAX_URL_LENGTH) {
                included = previous
                break
            }
        }
        val body = body()
        val url = base + encode(body)
        check(url.length <= MAX_URL_LENGTH && url.all { it.code < 128 })
        return IssueDraft(body, url, events.size - included.size)
    }

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8.name())
}
