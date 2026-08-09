package com.linktomac.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Injects touch gestures and limited system actions from the Mac while screen mirroring is
 * active. Must be enabled manually via Settings > Accessibility — Android doesn't let apps
 * grant this to themselves, same as notification-listener access in Phase 1.
 *
 * See docs/PROTOCOL.md's Phase 4 section for what is/isn't possible without root: gestures
 * (tap/swipe) and global actions (back/home/recents) work fully via the public
 * AccessibilityService APIs. Text entry is clipboard-set + ACTION_PASTE into the focused
 * field — not real key injection, which needs a signature-level permission
 * (`Instrumentation.sendKeySync`'s `INJECT_EVENTS`) no sideloaded app can hold.
 */
class InputInjectionAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "onServiceConnected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun performTap(normalizedX: Double, normalizedY: Double) {
        val (x, y) = toScreenPoint(normalizedX, normalizedY)
        Log.d(TAG, "performTap normalized=($normalizedX, $normalizedY) screen=($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), gestureCallback("tap"), null)
    }

    fun performSwipe(startXNorm: Double, startYNorm: Double, endXNorm: Double, endYNorm: Double, durationMs: Int) {
        val (startX, startY) = toScreenPoint(startXNorm, startYNorm)
        val (endX, endY) = toScreenPoint(endXNorm, endYNorm)
        Log.d(TAG, "performSwipe from=($startX, $startY) to=($endX, $endY)")
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.toLong().coerceAtLeast(1))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), gestureCallback("swipe"), null)
    }

    private fun gestureCallback(label: String) = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            Log.d(TAG, "$label completed")
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            Log.w(TAG, "$label cancelled")
        }
    }

    fun performGlobalKeyAction(action: String) {
        val globalAction = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        val result = performGlobalAction(globalAction)
        Log.d(TAG, "performGlobalKeyAction($action) result=$result")
    }

    /** Sets the clipboard and pastes into the currently-focused editable field — see the class
     *  doc for why this is the achievable substitute for real key injection. */
    fun pasteText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LinkToMac", text))
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        Log.d(TAG, "pasteText focusedNode=$focused")
        val result = focused?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "pasteText ACTION_PASTE result=$result")
    }

    private fun toScreenPoint(normalizedX: Double, normalizedY: Double): Pair<Float, Float> {
        val metrics: DisplayMetrics = resources.displayMetrics
        val x = (normalizedX * metrics.widthPixels).toFloat()
        val y = (normalizedY * metrics.heightPixels).toFloat()
        return x to y
    }

    companion object {
        private const val TAG = "InputInjection"
        private const val TAP_DURATION_MS = 50L

        var instance: InputInjectionAccessibilityService? = null
            private set
    }
}
