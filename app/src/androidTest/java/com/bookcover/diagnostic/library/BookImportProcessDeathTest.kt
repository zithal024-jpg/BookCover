package com.bookcover.diagnostic.library

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/** Real BookLibrary.import + OS-confirmed SIGKILL; no manually seeded import receipts or assets. */
@RunWith(AndroidJUnit4::class)
class BookImportProcessDeathTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val manager get() = target.getSystemService(ActivityManager::class.java)
    private val processName get() = "${target.packageName}:import_recovery"
    private val component get() = ComponentName(target, ImportProcessDeathActivity::class.java)

    @Test fun killedRealImportRecoversWithoutChangingExistingBooksAndLaterCommittedImportSurvivesDeath() = runBlocking<Unit> {
        check(target.packageName == "com.frankrodriguez.bookcover.qa")
        val nonce = UUID.randomUUID().toString()
        val root = File(target.noBackupFilesDir, "import-process-death-$nonce").apply { check(mkdir()) }
        val folder = File(root, "library")
        fun assets(id: String) = listOf("$id.epub", "$id.png", "$id.thumb.png").map { File(folder, it) }
        fun read(name: String) = runCatching {
            AtomicFile(File(root, name)).openRead().bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull()
        fun witness(name: String): JSONObject {
            var value: JSONObject? = null
            waitFor(name) {
                read("failed.json")?.let { fail("QA import: ${it.optString("error")}") }
                value = read(name)
                value != null
            }
            return value!!
        }
        fun launch(phase: String) {
            target.startActivity(Intent().setComponent(component).putExtra(ImportProcessDeathActivity.EXTRA_NONCE, nonce)
                .putExtra(ImportProcessDeathActivity.EXTRA_PHASE, phase)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        fun kill(checkpointWitness: JSONObject, checkpoint: String) {
            val pid = checkpointWitness.getInt("pid")
            assertEquals(processName, checkpointWitness.getString("process"))
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            witness("stopped-$pid.json")
            val process = manager.runningAppProcesses.single { it.processName == processName && it.pid == pid }
            assertEquals(target.applicationInfo.uid, process.uid)
            assertEquals(Process.myUid(), process.uid)
            assertEquals(process.uid, checkpointWitness.getInt("uid"))
            assertNotEquals("The instrumentation driver must survive", Process.myPid(), pid)
            assertFalse(File(root, "destroyed-$pid.json").exists())
            Process.killProcess(pid)
            waitFor("exact QA child PID $pid to disappear") { manager.runningAppProcesses.none { it.pid == pid } }
            var exit: ApplicationExitInfo? = null
            waitFor("Android SIGKILL evidence for $pid") {
                exit = manager.getHistoricalProcessExitReasons(target.packageName, pid, 1).firstOrNull()
                exit?.pid == pid && exit?.reason == ApplicationExitInfo.REASON_SIGNALED && exit?.status == 9
            }
            assertFalse("SIGKILL cannot run onDestroy", File(root, "destroyed-$pid.json").exists())
            assertFalse("SIGKILL cannot run import finally", File(root, "import-finally-$pid.json").exists())
            val evidence = "checkpoint=$checkpoint pid=$pid uid=${process.uid} OS_reason=${exit!!.reason} signal=${exit!!.status} driver=${Process.myPid()}"
            Log.i(TAG, evidence)
        }

        try {
            val sources = File(root, "sources").apply { mkdirs() }
            val context = object : ContextWrapper(target) { override fun getFilesDir() = root }
            val library = BookLibrary(context, ReadiumEngine(context)).apply { load() }
            val original = library.import(Uri.fromFile(GeneratedBooks.write(sources, "Existing process-death book"))).first
            val savedPlace = Locator(Url("chapter0.xhtml")!!, MediaType.XHTML,
                locations = Locator.Locations(totalProgression = 0.42))
            library.updatePosition(original.id, savedPlace)
            library.bookmark(original.id, savedPlace, name = "Existing saved place")
            val existing = library.book(original.id)!!
            val kept = (assets(original.id) + File(folder, "books.json") + File(folder, "books.last-good.json"))
                .associateWith { it.readBytes() }
            val incoming = GeneratedBooks.write(sources, "Incoming process-death book")
            val source = File(sources, "incoming.epub").also { incoming.copyTo(it) }
            val id = sha(source)
            val receipt = File(folder, "pending-import-$id")
            File(root, "config.json").writeText(JSONObject().put("incomingId", id).toString())
            val unmarked = File(folder, "${"e".repeat(64)}.epub").apply { writeText("Unmarked fixture must be preserved") }

            launch(ImportProcessDeathActivity.BLOCKED_IMPORT)
            val blocked = witness("checkpoint.json")
            assertEquals("real-import-final-assets-before-index", blocked.getString("checkpoint"))
            assertTrue(blocked.getBoolean("blockedInLibraryIndexReplace"))
            assertEquals(listOf(existing), LibraryIndex(folder).load().records)
            kept.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
            assertTrue(receipt.isFile)
            assertEquals("BookCover import 1\n$id\n", receipt.readText())
            assertEquals(id, sha(assets(id).first()))
            val abandoned = (assets(id) + receipt).associateWith { it.readBytes() }
            val activeCopies = folder.listFiles().orEmpty().filter { isImportTempName(it.name) }
            assertEquals("The real import still owns its input copy", 1, activeCopies.size)
            assertFalse(File(root, "unexpected-completion.json").exists())
            kill(blocked, "final-assets-before-index")
            // Prove the kill skipped rollback before allowing any recovery code to run.
            abandoned.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
            assertTrue(activeCopies.single().isFile)
            kept.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
            val fifo = File(folder, "books.last-good.json.tmp")
            assertTrue("Remove only our known test obstruction", OsConstants.S_ISFIFO(Os.lstat(fifo.absolutePath).st_mode))
            assertTrue("The dead writer's test pipe is removed", fifo.delete())

            launch(ImportProcessDeathActivity.RECOVER_AND_IMPORT)
            val recovered = witness("recovered.json")
            assertNotEquals(blocked.getInt("pid"), recovered.getInt("pid"))
            assertEquals(listOf(existing), readBookRecords(recovered.getJSONArray("records")).first)
            assertTrue("Only abandoned import assets removed", assets(id).none { it.exists() })
            assertFalse(receipt.exists())
            assertTrue(activeCopies.none { it.exists() })
            kept.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
            assertEquals("Unmarked fixture must be preserved", unmarked.readText())
            assertTrue(File(root, "continue-import").createNewFile())
            val committed = witness("committed.json")
            assertEquals(recovered.getInt("pid"), committed.getInt("pid"))
            assertFalse(committed.getBoolean("duplicateAdded"))
            val committedRecords = readBookRecords(committed.getJSONArray("records")).first
            assertEquals(2, committedRecords.size)
            assertEquals(existing, committedRecords.single { it.id == existing.id })
            val added = committedRecords.single { it.id == id }
            assertEquals(0.68, added.progression, 0.0)
            assertEquals(1, added.bookmarks.size)
            assertFalse(receipt.exists())
            val committedBytes = (assets(original.id) + assets(id) + File(folder, "books.json") + File(folder, "books.last-good.json"))
                .associateWith { it.readBytes() }
            kill(committed, "completed-import-with-position-bookmark-and-duplicate")
            launch(ImportProcessDeathActivity.VERIFY_COMMITTED)
            val verified = witness("verified.json")
            assertNotEquals(blocked.getInt("pid"), verified.getInt("pid"))
            assertNotEquals(committed.getInt("pid"), verified.getInt("pid"))
            assertEquals(committedRecords, readBookRecords(verified.getJSONArray("records")).first)
            committedBytes.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
            assertEquals("Unmarked fixture must be preserved", unmarked.readText())
            val evidence = "restarts=${blocked.getInt("pid")}->${recovered.getInt("pid")}->${verified.getInt("pid")} existingBytesPositionBookmark=unchanged abandonedAssetsAndTemp=removed committedAssetsAndMetadata=retained duplicateAdded=false"
            Log.i(TAG, evidence)
        } finally {
            manager.appTasks.filter { it.taskInfo?.baseActivity == component }.forEach { it.finishAndRemoveTask() }
            // Dispose only this harness process before deleting only this test's UUID fixture.
            manager.runningAppProcesses.filter { it.processName == processName }.forEach { child ->
                check(child.uid == target.applicationInfo.uid && child.pid != Process.myPid())
                Process.killProcess(child.pid)
                waitFor("QA child teardown") { manager.runningAppProcesses.none { it.pid == child.pid } }
            }
            root.deleteRecursively()
        }
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun waitFor(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 40_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(60)
        }
        fail("Timed out: $description")
    }

    companion object { private const val TAG = "F2ProcessDeath" }
}
