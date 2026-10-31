package com.bookcover.diagnostic.testing

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.library.ReadiumEngine
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.asInputStream

/** Reproduces R1's lazy-stream failure signature without WebView timing or fixture deletion. */
@OptIn(InternalReadiumApi::class)
class PublicationLifetimeTest {
    @Test fun lazyZipStreamAfterPublicationCloseReportsAnIoError(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa"))
        val folder = File(context.cacheDir, "publication-lifetime-${System.nanoTime()}").apply { mkdirs() }
        val file = File(folder, "embedded-font.epub")
        EpubCorpus.books.single { it.name == "embedded-font" }.write(file)
        val publication = ReadiumEngine(context).open(file)
        try {
            val link = publication.resources.first { it.url().toString().endsWith(".css") }
            val resource = checkNotNull(publication.get(link))
            // This is the same adapter used by WebViewServer. Length is known, but no ZIP entry
            // stream has been opened: Chromium may receive it before it asks for any bytes.
            resource.asInputStream().use { stream ->
                assertTrue(stream.available() > 0)
                val archive = zipArchive(publication.container)
                Log.i("PublicationLifetime", "handed out ${link.url()} from ${archive.name}; closing publication")
                publication.close()
                // FileZipContainer.close dispatches asynchronously. Observe actual closure rather
                // than waiting an arbitrary duration; keep the generated file intact throughout.
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) {
                        while (true) {
                            try { archive.size() } catch (_: IllegalStateException) { break }
                            yield()
                        }
                    }
                }
                assertTrue("No fixture deletion contributes to the closed-handle reproduction", file.isFile)
                Log.i("PublicationLifetime", "archive closed; reading the previously handed-out stream")
                assertThrows(IOException::class.java) { stream.read(ByteArray(16)) }
            }
        } finally {
            publication.close()
            folder.deleteRecursively()
        }
    }

    /**
     * QA-only observation of the pinned Readium 3.4.0 archive. No production hook or archive close
     * is injected. Fail clearly if a dependency change removes this implementation detail.
     */
    private fun zipArchive(root: Container<*>): ZipFile {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun find(value: Any): ZipFile? {
            if (!visited.add(value)) return null
            if (value is ZipFile) return value
            if (value is Iterable<*> && value !is Container<*>) {
                return value.firstNotNullOfOrNull { it?.let(::find) }
            }
            if (value !is Container<*>) return null
            var type: Class<*>? = value.javaClass
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    field.get(value)?.let { child -> find(child)?.let { return it } }
                }
                type = type.superclass
            }
            return null
        }
        return checkNotNull(find(root)) { "Expected a Readium 3.4.0 FileZipContainer archive" }
    }
}
