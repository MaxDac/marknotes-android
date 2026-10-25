package io.github.maxdac.marknotes.about

/** Which Android build is running, set by the host Application (`MarkNotesApp.edition`). */
enum class MarkNotesEdition {
    /** The libre build (F-Droid, Dropbox only), `:app` in this repo. */
    Libre,

    /** MarkNotes Plus (MaxDac/MarkNotes), which adds OneDrive and Proton Drive. */
    Pro
}

/** A MarkNotes app on another platform or edition, linked from Settings → About. */
enum class MarkNotesPeer(val label: String, val url: String) {
    AndroidLibre("MarkNotes for Android (F-Droid)", "https://f-droid.org/packages/io.github.maxdac.marknotes.libre/"),
    AndroidPro("MarkNotes Plus for Android (GitHub Releases)", "https://github.com/MaxDac/MarkNotes/releases"),
    Desktop("MarkNotes Desktop (GitHub Releases)", "https://github.com/MaxDac/marknotes-electron/releases"),
    Web("MarkNotes Web", "https://marknotes.fly.dev");

    companion object {
        /** The other MarkNotes apps, never the running [edition] itself. */
        fun others(edition: MarkNotesEdition): List<MarkNotesPeer> {
            val self = when (edition) {
                MarkNotesEdition.Libre -> AndroidLibre
                MarkNotesEdition.Pro -> AndroidPro
            }
            return entries.filter { it != self }
        }
    }
}
