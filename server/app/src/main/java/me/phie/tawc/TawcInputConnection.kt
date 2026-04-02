package me.phie.tawc

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.util.Log

/**
 * Custom InputConnection that bridges Android IME events to the Wayland
 * compositor via JNI. Gboard (and other IMEs) call these methods to send
 * text, composing state, and key events.
 */
class TawcInputConnection(view: View) : BaseInputConnection(view, true) {

    companion object {
        private const val TAG = "tawc"
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val str = text?.toString() ?: return false
        Log.d(TAG, "InputConnection.commitText: \"$str\"")
        NativeBridge.nativeCommitText(str)
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val str = text?.toString() ?: ""
        Log.d(TAG, "InputConnection.setComposingText: \"$str\"")
        NativeBridge.nativeSetComposingText(str)
        return true
    }

    override fun finishComposingText(): Boolean {
        Log.d(TAG, "InputConnection.finishComposingText")
        NativeBridge.nativeFinishComposingText()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        Log.d(TAG, "InputConnection.deleteSurroundingText: before=$beforeLength after=$afterLength")
        NativeBridge.nativeDeleteSurroundingText(beforeLength, afterLength)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        Log.d(TAG, "InputConnection.performEditorAction: actionCode=$actionCode")
        // IME action button (Go, Done, Search, etc.) — treat as Enter
        NativeBridge.nativeSendKeyEvent(KeyEvent.KEYCODE_ENTER)
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // Only handle key-down events to avoid double-processing
        if (event.action != KeyEvent.ACTION_DOWN) return true

        Log.d(TAG, "InputConnection.sendKeyEvent: keyCode=${event.keyCode}")
        NativeBridge.nativeSendKeyEvent(event.keyCode)
        return true
    }
}
