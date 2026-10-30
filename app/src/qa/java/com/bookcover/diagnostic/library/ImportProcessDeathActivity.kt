package com.bookcover.diagnostic.library

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/** QA-only real-import worker. SIGKILL affects this private process, never the test runner. */
class ImportProcessDeathActivity : Activity() {
    // Deliberately independent of onStop: backgrounding must not turn process death into cancellation.
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var root: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.bookcover.diagnostic.qa")
        check(Application.getProcessName() == "$packageName:import_recovery")
        val nonce = requireNotNull(intent.getStringExtra(EXTRA_NONCE)).also {
            require(it.matches(Regex("[a-f0-9-]{36}")))
        }
        root = File(applicationContext.noBackupFilesDir, "import-process-death-$nonce")
        check(root.isDirectory) { "The driver must prepare a disposable fixture first" }
        val phase = requireNotNull(intent.getStringExtra(EXTRA_PHASE))
        work.launch {
            try {
                val context = object : ContextWrapper(applicationContext) { override fun getFilesDir() = root }
                val library = BookLibrary(context, ReadiumEngine(context))
                when (phase) {
                    BLOCKED_IMPORT -> blockedImport(library)
                    RECOVER_AND_IMPORT -> recoverAndImport(library)
                    VERIFY_COMMITTED -> {
                        library.load()
                        write("verified.json", identity().put("records", records(library)))
                        awaitCancellation()
                    }
                    else -> error("Unknown QA phase")
                }
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (error: Exception) {
                write("failed.json", identity().put("error", "${error.javaClass.simpleName}: ${error.message}"))
            }
        }
    }

    private suspend fun blockedImport(library: BookLibrary) {
        check(!File(root, "checkpoint.json").exists()) { "Never prepare a killed import again" }
        library.load()
        val config = read("config.json")
        val id = config.getString("incomingId")
        val fifo = File(root, "library/books.last-good.json.tmp")
        // An unopened pipe blocks the real backup write without changing the production code.
        // The driver removes only this test obstruction, and only after the writer is dead.
        Os.mkfifo(fifo.absolutePath, 384) // 0600: this QA UID only.
        val import = work.launch {
            try {
                library.import(Uri.fromFile(File(root, "sources/incoming.epub")))
                write("unexpected-completion.json", identity())
            } catch (error: Exception) {
                write("failed.json", identity().put("error", "Import returned: ${error.javaClass.simpleName}"))
            } finally {
                write("import-finally-${Process.myPid()}.json", identity())
            }
        }
        withTimeout(30_000) {
            while (true) {
                check(import.isActive) { "Import did not remain active" }
                val blockedInSave = Thread.getAllStackTraces().values.any { stack ->
                    stack.any { it.className == BookLibrary::class.java.name && it.methodName.startsWith("importStream") } &&
                    stack.any { it.className == LibraryIndex::class.java.name && it.methodName == "replace" } &&
                        stack.any { it.className == "java.io.FileOutputStream" && it.methodName == "<init>" }
                }
                if (blockedInSave && listOf("$id.epub", "$id.png", "$id.thumb.png", "pending-import-$id")
                        .all { File(root, "library/$it").length() > 0 }) break
                delay(40)
            }
        }
        check(library.book(id) == null)
        write("checkpoint.json", identity().put("checkpoint", "real-import-final-assets-before-index")
            .put("incomingId", id).put("blockedInLibraryIndexReplace", true))
        awaitCancellation()
    }

    private suspend fun recoverAndImport(library: BookLibrary) {
        val checkpoint = read("checkpoint.json")
        check(checkpoint.getInt("pid") != Process.myPid()) { "Recovery needs a new process" }
        library.load()
        write("recovered.json", identity().put("records", records(library)))
        withTimeout(40_000) { while (!File(root, "continue-import").exists()) delay(40) }
        val uri = Uri.fromFile(File(root, "sources/incoming.epub"))
        val (book, added) = library.import(uri)
        check(added)
        val locator = Locator(Url("chapter0.xhtml")!!, MediaType.XHTML,
            locations = Locator.Locations(totalProgression = 0.68))
        library.updatePosition(book.id, locator)
        library.bookmark(book.id, locator, name = "Committed after restart")
        check(!library.import(uri).second) { "Reimport must be a duplicate" }
        write("committed.json", identity().put("records", records(library)).put("duplicateAdded", false))
        awaitCancellation()
    }

    override fun onStop() {
        super.onStop()
        if (::root.isInitialized) write("stopped-${Process.myPid()}.json", identity())
    }

    override fun onDestroy() {
        if (::root.isInitialized) write("destroyed-${Process.myPid()}.json", identity())
        work.cancel()
        super.onDestroy()
    }

    private fun identity() = JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid())
        .put("process", Application.getProcessName()).put("taskId", taskId)
    private fun records(library: BookLibrary) = JSONArray().apply { library.books.value.forEach { put(it.toJson()) } }
    private fun read(name: String) = AtomicFile(File(root, name)).openRead().bufferedReader().use { JSONObject(it.readText()) }
    private fun write(name: String, value: JSONObject) {
        val file = AtomicFile(File(root, name))
        val output = file.startWrite()
        try { output.write(value.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    companion object {
        const val EXTRA_NONCE = "importDeathNonce"
        const val EXTRA_PHASE = "importDeathPhase"
        const val BLOCKED_IMPORT = "blocked-import"
        const val RECOVER_AND_IMPORT = "recover-and-import"
        const val VERIFY_COMMITTED = "verify-committed"
    }
}
