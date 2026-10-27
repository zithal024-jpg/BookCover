package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.library.LibraryIndex
import com.bookcover.diagnostic.streak.ReadingDayStore
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SIGKILL of a separate, non-instrumented QA reader; the default-process driver survives. */
@RunWith(AndroidJUnit4::class)
class ReaderProcessDeathTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val manager get() = target.getSystemService(ActivityManager::class.java)

    @Test fun backgroundReaderRecoversItsRetainedTaskAfterTwoActualProcessKills() {
        check(target.packageName == "com.bookcover.diagnostic.qa")
        val nonce = UUID.randomUUID().toString()
        val root = File(target.noBackupFilesDir, "process-recovery-$nonce").apply { mkdirs() }
        val component = ComponentName(target, ProcessRecoveryActivity::class.java)
        val processName = "${target.packageName}:process_recovery"
        fun read(name: String) = runCatching {
            AtomicFile(File(root, name)).openRead().bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull()
        fun witness(name: String, predicate: (JSONObject) -> Boolean = { true }): JSONObject {
            var result: JSONObject? = null
            waitFor(name) {
                read("failed.json")?.let { fail("QA reader: ${it.optString("error")}") }
                result = read(name)?.takeIf(predicate)
                result != null
            }
            return result!!
        }
        fun readingMillis() = ReadingDayStore(File(root, "library/reading-days.json")).load().days.values.sumOf { it.millis }
        var recordedTask = -1
        try {
            target.startActivity(Intent().setComponent(component).putExtra(ProcessRecoveryActivity.EXTRA_NONCE, nonce)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val prepared = witness("prepared.json")
            val expected = witness("expected.json")
            recordedTask = prepared.getInt("taskId")
            var currentPid = prepared.getInt("pid")
            var previousReading = 0L
            var resumedAt: Long? = null
            repeat(2) { cycle ->
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                val stopped = witness("stopped.json") { it.optInt("pid") == currentPid }
                val saved = witness("saved.json") { it.optInt("pid") == currentPid }
                assertEquals(expected.getString("bookId"), saved.getString("bookId"))
                assertTrue(JSONObject(saved.getString("locator")).getJSONObject("locations").has("cssSelector"))
                waitFor("onStop position/bookmark and pause time persisted") {
                    val record = LibraryIndex(File(root, "library")).load().records.singleOrNull()
                    record?.id == expected.getString("bookId") && record.bookmarks == listOf(expected.getString("bookmark")) &&
                        record.locator != null && readingMillis() >= 2_000
                }
                SystemClock.sleep(300) // Let the last pause credit finish before comparing disk totals.
                val pausedReading = readingMillis()
                assertTrue("Accumulated time cannot reset", pausedReading >= previousReading)
                val foregroundBound = if (resumedAt == null) stopped.getLong("stoppedAt") - expected.getLong("openedAt")
                    else previousReading + stopped.getLong("stoppedAt") - resumedAt!!
                assertTrue("No duplicated/dead-time credit after pause: $pausedReading > $foregroundBound", pausedReading <= foregroundBound + 300)
                SystemClock.sleep(1_500)
                assertEquals("Background time is excluded before killing", pausedReading, readingMillis())
                val process = manager.runningAppProcesses.single { it.processName == processName }
                assertEquals(currentPid, process.pid)
                assertEquals(Process.myUid(), process.uid)
                assertEquals(target.applicationInfo.uid, process.uid)
                assertNotEquals("Never kill the instrumentation driver", Process.myPid(), process.pid)
                assertFalse(File(root, "destroyed-$currentPid.json").exists())
                // Kernel permission checks also restrict this call to our QA UID; no shell package kill.
                Process.killProcess(process.pid)
                waitFor("the exact reader PID disappears") { manager.runningAppProcesses.none { it.pid == currentPid } }
                assertFalse("SIGKILL must not call onDestroy", File(root, "destroyed-$currentPid.json").exists())
                var exit: ApplicationExitInfo? = null
                waitFor("Android records the reader's SIGKILL") {
                    exit = manager.getHistoricalProcessExitReasons(target.packageName, currentPid, 1).firstOrNull()
                    exit?.pid == currentPid && exit?.reason == ApplicationExitInfo.REASON_SIGNALED && exit?.status == 9
                }
                SystemClock.sleep(1_500)
                assertEquals("Dead-process time is excluded", pausedReading, readingMillis())
                val task = manager.appTasks.single { it.taskInfo?.taskId == recordedTask }
                val taskInfo = requireNotNull(task.taskInfo)
                assertEquals(component, taskInfo.baseActivity)
                assertEquals(target.packageName, taskInfo.baseActivity?.packageName)
                File(root, "restored.json").delete() // Only this test's own QA witness.
                val control = JSONObject().put("oldPid", currentPid).put("readingMillis", pausedReading)
                    .put("resumeAt", SystemClock.elapsedRealtime())
                resumedAt = control.getLong("resumeAt")
                File(root, "resume-request.json").writeText(control.toString())
                task.moveToFront() // Restore the retained task; do not create a fresh test reader.
                val restored = witness("restored.json") { it.optInt("pid") != currentPid }
                assertEquals(recordedTask, restored.getInt("taskId"))
                assertEquals(currentPid, restored.getInt("savedPid"))
                assertTrue(restored.getBoolean("bundleRestored"))
                assertTrue(restored.getBoolean("passageVisible"))
                assertEquals(1, restored.getInt("navigatorCount"))
                Log.i("ReaderProcessDeathTest", "cycle=${cycle + 1}; oldPID=$currentPid; newPID=${restored.getInt("pid")}; task=$recordedTask; OS reason=${exit!!.reason}; signal=${exit!!.status}; retained passage/settings/bookmark/time verified")
                previousReading = pausedReading
                currentPid = restored.getInt("pid")
            }
            // Check the final pause too: deferred credits must not hide background/dead time.
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            val finalStop = witness("stopped.json") { it.optInt("pid") == currentPid }
            SystemClock.sleep(300)
            val finalReading = readingMillis()
            assertTrue(finalReading >= previousReading)
            assertTrue("Final pause must credit only new foreground time",
                finalReading <= previousReading + finalStop.getLong("stoppedAt") - resumedAt!! + 300)
            SystemClock.sleep(1_500)
            assertEquals("Final background interval is excluded", finalReading, readingMillis())
        } finally {
            manager.appTasks.firstOrNull { it.taskInfo?.taskId == recordedTask && it.taskInfo?.baseActivity == component }
                ?.finishAndRemoveTask()
        }
    }

    private fun waitFor(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 40_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(60)
        }
        fail("Timed out: $description")
    }
}
