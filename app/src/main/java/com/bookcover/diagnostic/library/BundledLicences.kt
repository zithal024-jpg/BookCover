package com.bookcover.diagnostic.library

/**
 * One third-party component shipped in the APK (BC-12). [textAsset] is the full licence text in
 * the app's assets; [textFromReadium] marks files Readium bundles itself. [covers] lists the Maven
 * groups it accounts for, so a unit test can catch a new library that has no notice.
 */
internal data class LicenceNotice(
    val component: String,
    val usedFor: String,
    val licence: String,
    val credit: String,
    val textAsset: String,
    val covers: List<String> = emptyList(),
    val textFromReadium: Boolean = false,
)

internal val BUNDLED_LICENCES = listOf(
    LicenceNotice(
        "OpenDyslexic font", "The OpenDyslexic reading font",
        "CC BY 3.0, over the Bitstream Vera font licence",
        "OpenDyslexic 2.001 © 2012 Abelardo Gonzalez, based on Bitstream Vera © 2003 Bitstream, Inc. Not modified.",
        "OPENDYSLEXIC-LICENSE.txt",
    ),
    LicenceNotice(
        "Readium Kotlin Toolkit", "Opening and displaying EPUB and PDF books, including Readium CSS",
        "BSD 3-Clause", "© 2017 Readium", "READIUM-LICENSE.txt", listOf("org.readium"),
    ),
    LicenceNotice(
        "Readium comic player libraries", "Bundled with Readium's comic (Divina) player",
        "MIT", "PixiJS, resource-loader, Hammer.JS © 2016 Jorik Tangelder, punycode by Mathias Bynens",
        "READIUM-SCRIPTS-LICENSE.txt",
    ),
    LicenceNotice(
        "AccessibleDfA font", "Readium's accessible reading font", "SIL Open Font License 1.1",
        "© 2015 Orange, Reserved Font Name Accessible-Dfa",
        "readium/readium-css/fonts/LICENSE-AccessibleDfa", textFromReadium = true,
    ),
    LicenceNotice(
        "iA Writer Duospace font", "Readium's monospace reading font", "SIL Open Font License 1.1",
        "Based on IBM Plex © 2017 IBM Corp., Reserved Font Name \"Plex\"",
        "readium/readium-css/fonts/LICENSE-IaWriterDuospace.md", textFromReadium = true,
    ),
    LicenceNotice(
        "jsoup", "Cleaning book HTML before display", "MIT", "© 2009–2026 Jonathan Hedley",
        "JSOUP-LICENSE.txt", listOf("org.jsoup"),
    ),
    LicenceNotice(
        "PdfiumAndroid and PDFium", "Rendering PDF pages",
        "Apache License 2.0 and PDFium BSD 3-Clause",
        "PdfiumAndroid © 2015 Bekket McClane, © 2016 Bartosz Schiller; PDFium © 2014 PDFium Authors",
        "PDFIUM-LICENSE.txt", listOf("com.github.marain87"),
    ),
    LicenceNotice(
        "AndroidPdfViewer", "Displaying PDF pages", "Apache License 2.0",
        "© 2017 Bartosz Schiller and contributors", "APACHE-2.0-LICENSE.txt",
    ),
    LicenceNotice(
        "AndroidX, Jetpack Compose and Kotlin libraries",
        "App screens, window and fold support, media, Kotlin language and coroutines",
        "Apache License 2.0",
        "The Android Open Source Project and Google (AndroidX, Compose, WindowManager, Media3, Guava), JetBrains (Kotlin, " +
            "kotlinx coroutines, serialization and datetime, annotations), Jake Wharton (Timber), mcxiaoke (Koi), JSpecify",
        "APACHE-2.0-LICENSE.txt",
        listOf("androidx", "com.google.guava", "com.jakewharton.timber", "com.mcxiaoke.koi", "org.jetbrains", "org.jspecify"),
    ),
    LicenceNotice(
        "Java library desugaring", "Date and time support (parts of the OpenJDK class library)",
        "GPL v2 with the Classpath Exception", "© Oracle and/or its affiliates; Android desugar_jdk_libs 2.1.5",
        "OPENJDK-GPL2-CLASSPATH-LICENSE.txt", listOf("com.android.tools"),
    ),
)
