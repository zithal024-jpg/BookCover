package com.bookcover.diagnostic.testing

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Reset book fixtures without changing the shared library's cached history and reward files. */
internal fun resetQaBookFixtures() {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    check(target.packageName.endsWith(".qa")) { "Book fixtures may only reset the isolated QA app." }
    val files = target.filesDir.canonicalFile
    val directory = File(files, "library").canonicalFile
    check(directory.parentFile == files) { "QA book fixtures must stay inside the QA files directory." }
    if (!directory.exists()) return
    check(directory.isDirectory) { "The QA library fixture path is not a directory." }
    val entries = checkNotNull(directory.listFiles()) { "The QA library fixtures could not be listed." }
    entries.forEach { entry ->
        // Keep primary, backup and atomic temporary files behind the process-wide stores.
        if (entry.name.startsWith("reading-days.json") || entry.name.startsWith("reading-relics.json"))
            return@forEach
        check(entry.deleteRecursively()) { "Could not remove QA book fixture ${entry.name}." }
    }
}
