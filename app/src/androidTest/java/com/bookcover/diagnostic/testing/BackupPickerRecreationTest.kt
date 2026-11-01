package com.bookcover.diagnostic.testing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.LibraryIndex
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Picker results returned after controlled recreation, not a real process kill or memory-pressure test. */
@RunWith(AndroidJUnit4::class)
class BackupPickerRecreationTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val first = BookRecord("a".repeat(64), "Generated first", "QA", 1)
    private val second = BookRecord("b".repeat(64), "Generated second", "QA", 2)
    private lateinit var image: File

    @Before fun prepareQaOnly() {
        check(target.packageName == "com.bookcover.diagnostic.qa")
        val folder = File(target.filesDir, "library").apply { mkdirs() }
        resetQaBookFixtures()
        LibraryIndex(folder).apply { load(); save(listOf(first, second)) }
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
        image = File.createTempFile("picker-cover-", ".png", target.cacheDir)
        val bitmap = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitIdle()
    }

    @After fun close() { scenario?.close(); if (::image.isInitialized) image.delete() }

    @Test fun pendingCoverIdentityAndResultRoutingSurviveRecreation() {
        returnCoverAfterRecreation(first.id, Uri.fromFile(image))
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        awaitIdle()
        onActivity {
            assertTrue(it.libraryForTests.book(first.id)!!.coverRevision > 0)
            assertEquals(0L, it.libraryForTests.book(second.id)!!.coverRevision)
            assertNull(pendingTarget(it))
            assertTrue(it.messageForTests.startsWith("Cover updated."))
        }
    }

    @Test fun removedTargetCannotChangeAnotherCoverAfterRecreation() {
        returnCoverAfterRecreation("c".repeat(64), Uri.fromFile(image))
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        awaitIdle()
        onActivity {
            assertTrue(it.libraryForTests.books.value.all { book -> book.coverRevision == 0L })
            assertTrue(it.messageForTests.contains("no longer in your library"))
            assertNull(pendingTarget(it))
        }
    }

    @Test fun cancelledPickerConsumesRestoredTarget() {
        returnCoverAfterRecreation(first.id, null)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        awaitIdle()
        onActivity {
            assertNull(pendingTarget(it))
            assertTrue(it.libraryForTests.books.value.all { book -> book.coverRevision == 0L })
        }
    }

    @Test fun unusableUriDoesNotUpdateAnyCoverOrReportSuccess() {
        returnCoverAfterRecreation(first.id, Uri.fromFile(File(target.cacheDir, "missing-picker-image")))
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        awaitIdle()
        onActivity {
            assertNull(pendingTarget(it))
            assertTrue(it.libraryForTests.books.value.all { book -> book.coverRevision == 0L })
            assertFalse(it.messageForTests.startsWith("Cover updated."))
            assertTrue(it.messageForTests.contains("try again", ignoreCase = true) || it.messageForTests.contains("another picture"))
        }
    }

    private fun returnCoverAfterRecreation(id: String, uri: Uri?) {
        scenario!!.moveToState(Lifecycle.State.CREATED)
        var originalRequestCode = 0
        onActivity { activity ->
            MainActivity::class.java.getDeclaredField("coverBookId").apply { isAccessible = true }.set(activity, id)
            val routing = Bundle().also(activity.activityResultRegistry::onSaveInstanceState)
            // selectCover is the second registerForActivityResult declaration in MainActivity.
            val keys = routing.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!
            val codes = routing.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!
            originalRequestCode = codes[keys.indexOf("activity_rq#1").also { check(it >= 0) }]
        }
        scenario!!.recreate()
        onActivity { activity ->
            assertTrue(activity.activityResultRegistry.dispatchResult(originalRequestCode,
                if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let { Intent().setData(it) }))
        }
    }

    private fun pendingTarget(activity: MainActivity): String? =
        MainActivity::class.java.getDeclaredField("coverBookId").apply { isAccessible = true }.get(activity) as String?

    private fun onActivity(block: (MainActivity) -> Unit) { scenario!!.onActivity(block) }

    private fun awaitIdle() {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var busy = true
            onActivity { busy = it.busyForTests }
            if (!busy) return
            SystemClock.sleep(40)
        }
        fail("Library/picker operation did not finish")
    }
}
