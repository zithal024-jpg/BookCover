package com.bookcover.diagnostic.testing

import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/** A real finger pointer; this utility exists only in the isolated instrumentation APK. */
internal fun qaFingerEvent(down: Long, time: Long, action: Int, x: Float, y: Float): MotionEvent {
    val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
    val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
    return MotionEvent.obtain(down, time, action, 1, arrayOf(pointer), arrayOf(coordinates),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
}

/**
 * Calibrated 180ms native fling. Batched 10ms samples preserve the physical gesture timing when
 * synchronous injection itself takes >40ms and would clear Android's velocity history. Every
 * timestamp is already in the past. No app velocity threshold or private state is modified.
 * Paper-turn tests retain their live, frame-by-frame drags and separate stopped-drag checks.
 */
internal fun Instrumentation.injectCalibratedNativeFling(startX: Float, endX: Float, y: Float) {
    val end = SystemClock.uptimeMillis() - 1
    val down = end - 180
    qaFingerEvent(down, down, MotionEvent.ACTION_DOWN, startX, y).also { sendPointerSync(it); it.recycle() }
    val move = qaFingerEvent(down, down + 10, MotionEvent.ACTION_MOVE, startX + (endX - startX) / 18, y)
    for (step in 2..18) {
        move.addBatch(down + step * 10, startX + (endX - startX) * step / 18, y, 1f, 1f, 0)
    }
    move.also { sendPointerSync(it); it.recycle() }
    qaFingerEvent(down, end + 1, MotionEvent.ACTION_UP, endX, y).also { sendPointerSync(it); it.recycle() }
    waitForIdleSync()
}
