package com.autoclicker.app.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tracks which display the user is currently looking at.
 *
 * On a normal phone this is always [Display.DEFAULT_DISPLAY] (0). Some
 * environments, notably the MuMu Android 15 emulator, launch every app on its
 * own virtual display and only the home launcher lives on display 0. If the
 * overlay windows and the dispatched gestures always target display 0, they are
 * only visible / effective on the home screen.
 *
 * [AutoClickerAccessibilityService] feeds this tracker from window-state events,
 * [FloatingOverlayService] listens to it to move its windows, and gestures are
 * dispatched to [displayId].
 */
object ActiveDisplayTracker {

    private const val TAG = "ActiveDisplay"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Int) -> Unit>()

    /** The display the foreground app is on. */
    @Volatile
    var displayId: Int = Display.DEFAULT_DISPLAY
        private set

    /**
     * Report that the foreground content is now on [newDisplayId].
     * Invalid IDs and no-op updates are ignored. Listeners run on the main thread.
     */
    fun update(newDisplayId: Int) {
        if (newDisplayId < 0 || newDisplayId == displayId) return
        Log.d(TAG, "Active display changed: $displayId -> $newDisplayId")
        displayId = newDisplayId
        val notify = Runnable { listeners.forEach { it(newDisplayId) } }
        if (Looper.myLooper() == Looper.getMainLooper()) notify.run() else mainHandler.post(notify)
    }

    fun addListener(listener: (Int) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Int) -> Unit) {
        listeners.remove(listener)
    }
}
