plugins {
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

// OneDrive marks generated directories read-only/reparse points on this Windows
// computer, which prevents Android's incremental resource merger deleting them.
// Keep source in the requested folder and generated files in a local cache.
val generatedRoot = providers.gradleProperty("bookcover.buildRoot").orNull
    ?: System.getenv("LOCALAPPDATA")?.let { "$it/BookCoverBuild/project-output" }
if (generatedRoot != null) {
    layout.buildDirectory.set(file("$generatedRoot/root"))
    subprojects {
        layout.buildDirectory.set(file("$generatedRoot/$name"))
    }
}
