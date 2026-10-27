package com.bookcover.diagnostic.library

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** BC-12: About → Licences lists every bundled licence, with the full text shipped in the APK. */
class BundledLicencesTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    private val buildScript = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile }

    @Test fun everyNoticeIsCompleteAndUnique() {
        assertTrue(BUNDLED_LICENCES.size >= 7)
        BUNDLED_LICENCES.forEach { notice ->
            listOf(notice.component, notice.usedFor, notice.licence, notice.credit, notice.textAsset).forEach {
                assertTrue("${notice.component}: blank field", it.isNotBlank())
            }
        }
        assertEquals(BUNDLED_LICENCES.size, BUNDLED_LICENCES.map { it.component }.toSet().size)
    }

    @Test fun ourLicenceTextsShipAsAssets() {
        BUNDLED_LICENCES.filter { !it.textFromReadium }.forEach { notice ->
            val file = File(assets, notice.textAsset)
            assertTrue("${notice.textAsset} missing", file.isFile && file.length() > 500)
        }
    }

    @Test fun openDyslexicIsCcBy30OverBitstreamVeraNotOfl() {
        val notice = BUNDLED_LICENCES.single { it.component.startsWith("OpenDyslexic") }
        assertTrue(notice.licence.contains("CC BY 3.0"))
        assertTrue(notice.licence.contains("Bitstream Vera"))
        assertFalse(notice.licence.contains("OFL") || notice.licence.contains("Open Font"))
        val text = File(assets, notice.textAsset).readText()
        assertTrue(text.contains("Creative Commons Attribution 3.0"))
        assertTrue(text.contains("Bitstream Vera Fonts Copyright"))
        assertFalse(text.contains("Open Font License"))
    }

    @Test fun licenceTextsAreTheRealDocuments() {
        fun text(component: String) = File(assets, BUNDLED_LICENCES.single { it.component.startsWith(component) }.textAsset).readText()
        assertTrue(text("AndroidX").contains("Apache License") && text("AndroidX").contains("Version 2.0, January 2004"))
        assertTrue(text("Java library").contains("GNU General Public License") && text("Java library").contains("\"CLASSPATH\" EXCEPTION"))
        assertTrue(text("jsoup").contains("The MIT License") && text("jsoup").contains("Jonathan Hedley"))
        assertTrue(text("Readium Kotlin").contains("BSD 3-Clause"))
        val scripts = text("Readium comic")
        listOf("PixiJS", "resource-loader", "Hammer.JS", "punycode", "Permission is hereby granted").forEach {
            assertTrue("$it missing", scripts.contains(it))
        }
    }

    @Test fun everyLibraryInTheBuildIsCovered() {
        val declared = Regex("""^\s*(implementation|coreLibraryDesugaring)\((platform\()?"([^:"]+):""", RegexOption.MULTILINE)
            .findAll(buildScript.readText()).map { it.groupValues[3] }.toSet()
        // Runtime libraries pulled in by those (./gradlew :app:dependencies, debugRuntimeClasspath, 2026-10-26).
        val transitive = setOf("androidx.media3", "androidx.appcompat", "com.google.guava", "com.jakewharton.timber",
            "com.mcxiaoke.koi", "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains", "org.jspecify")
        (declared + transitive).forEach { group ->
            assertTrue("$group has no licence notice", BUNDLED_LICENCES.any { notice -> notice.covers.any { group == it || group.startsWith("$it.") } })
        }
        assertTrue("org.readium.kotlin-toolkit" in declared && "com.android.tools" in declared)
    }
}
