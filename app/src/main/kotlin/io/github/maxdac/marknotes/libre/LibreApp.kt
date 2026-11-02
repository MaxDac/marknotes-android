package io.github.maxdac.marknotes.libre

import io.github.maxdac.marknotes.MarkNotesApp
import io.github.maxdac.marknotes.about.MarkNotesEdition

/** The libre (F-Droid) app: notes stay on the device; it offers no cloud provider. */
class LibreApp : MarkNotesApp() {
    override val edition: MarkNotesEdition = MarkNotesEdition.Libre
}
