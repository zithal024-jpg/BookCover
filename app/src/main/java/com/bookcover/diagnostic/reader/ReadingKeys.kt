package com.bookcover.diagnostic.reader

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window

/**
 * Observe hardware keys before focused WebViews, while preserving the window's normal dispatch.
 * [onInput] sees each key and touch-down that reaches this window, without consuming it. It is not
 * Activity.onUserInteraction, which Android also calls when the user leaves the activity (BC-22).
 */
internal fun Activity.installReadingKeyHandler(onInput: () -> Unit = {}, reader: () -> EpubSession?) {
    val original = window.callback
    window.callback = object : Window.Callback by original {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            onInput()
            return reader()?.dispatchKeyEvent(event) == true || original.dispatchKeyEvent(event)
        }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onInput()
            return original.dispatchTouchEvent(event)
        }
    }
}

internal enum class ReadingPageKey { VOLUME_UP, VOLUME_DOWN, PAGE_UP, PAGE_DOWN, LEFT, RIGHT }
internal enum class ReadingPageTurn { PREVIOUS, NEXT }

/** Capture a whole press, including its release, and turn only once when a key is held. */
internal class ReadingKeyPolicy {
    private val pressed = mutableSetOf<ReadingPageKey>()

    fun handle(
        key: ReadingPageKey, down: Boolean, repeated: Boolean, canNavigate: Boolean,
        volumeEnabled: Boolean, rightToLeft: Boolean, turn: (ReadingPageTurn) -> Unit,
    ): Boolean {
        if (!down) return pressed.remove(key)
        if (key in pressed) return true
        if (!canNavigate || repeated) return false
        if (!volumeEnabled && key in listOf(ReadingPageKey.VOLUME_UP, ReadingPageKey.VOLUME_DOWN)) return false
        val action = when (key) {
            ReadingPageKey.VOLUME_UP, ReadingPageKey.PAGE_UP -> ReadingPageTurn.PREVIOUS
            ReadingPageKey.VOLUME_DOWN, ReadingPageKey.PAGE_DOWN -> ReadingPageTurn.NEXT
            ReadingPageKey.LEFT -> if (rightToLeft) ReadingPageTurn.NEXT else ReadingPageTurn.PREVIOUS
            ReadingPageKey.RIGHT -> if (rightToLeft) ReadingPageTurn.PREVIOUS else ReadingPageTurn.NEXT
        }
        pressed += key
        turn(action)
        return true
    }
}

internal fun KeyEvent.readingPageKey(): ReadingPageKey? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP -> ReadingPageKey.VOLUME_UP
    KeyEvent.KEYCODE_VOLUME_DOWN -> ReadingPageKey.VOLUME_DOWN
    KeyEvent.KEYCODE_PAGE_UP -> ReadingPageKey.PAGE_UP
    KeyEvent.KEYCODE_PAGE_DOWN -> ReadingPageKey.PAGE_DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> ReadingPageKey.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> ReadingPageKey.RIGHT
    else -> null
}
