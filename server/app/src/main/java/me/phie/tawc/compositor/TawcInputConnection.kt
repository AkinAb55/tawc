package me.phie.tawc.compositor

import android.text.Editable
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.util.Log

/**
 * Custom InputConnection that bridges Android IME events to the Wayland
 * compositor via JNI.
 *
 * # Editable mirror of the Wayland buffer
 *
 * The IME (Gboard, OpenBoard) reads from the Editable via
 * `getTextBeforeCursor`/`getExtractedText`. We keep it in sync with
 * the Wayland client's actual text via two channels:
 *
 * 1. **Outbound:** every IME method calls `super` first to update the
 *    Editable, then JNI to forward to the compositor.
 * 2. **Inbound:** when the Wayland client commits a `set_surrounding_text`,
 *    the compositor calls reverse-JNI [updateFromCompositor], which
 *    overwrites the Editable with the truth (handles autocomplete,
 *    paste, undo, etc.).
 *
 * # Why we don't propagate `setComposingRegion`-driven replacements
 *
 * Android's `setComposingRegion(s, e)` marks already-committed text as
 * a composing region; on Wayland the bytes are still committed text
 * with no preedit. A naive bridge would translate the next
 * `setComposingText("X")` into `delete_surrounding_text + preedit_string`
 * to replace the marked region. But IMEs (Gboard, OpenBoard) issue
 * `setComposingRegion` aggressively — typically marking the word at the
 * cursor on every cursor change for predictive correction tracking.
 * Propagating that as a real delete + replace causes the "currently
 * active word moves with the cursor" bug: every keystroke after a click
 * physically replaces the word the IME's predictor flagged.
 *
 * So we deliberately do NOT propagate composing-region replacement.
 * `setComposingText` translates to plain `preedit_string`. The client
 * shows the preedit overlay at the cursor; the original committed text
 * stays put. Real autocorrect (commit-on-top-of-typed-preedit) still
 * works because the protocol's done-ordering replaces the existing
 * preedit on commit.
 *
 * `BaseInputConnection(view, true)` (`fullEditor=true`) means
 * `mFallbackMode=false`, so `sendCurrentText()` is a no-op — calling
 * `super` does NOT cause duplicate input via key events.
 */
class TawcInputConnection(private val targetView: View) : BaseInputConnection(targetView, true) {

    init {
        // Cache as the active IC so reverse-JNI updates and broadcast tests
        // can find it. Also makes broadcasts share Editable state, which is
        // critical for multi-step IME flows (compose-then-commit, etc.).
        NativeBridge.activeInputConnection = this
    }

    override fun closeConnection() {
        if (NativeBridge.activeInputConnection === this) {
            NativeBridge.activeInputConnection = null
        }
        super.closeConnection()
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val str = text?.toString() ?: return false
        Log.d(TAG, "InputConnection.commitText: \"$str\" cursorPos=$newCursorPosition")
        super.commitText(text, newCursorPosition)
        NativeBridge.nativeCommitText(str, 0, 0)
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val str = text?.toString() ?: ""
        Log.d(TAG, "InputConnection.setComposingText: \"$str\" cursorPos=$newCursorPosition")
        super.setComposingText(text, newCursorPosition)
        NativeBridge.nativeSetComposingText(str, 0, 0)
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        Log.d(TAG, "InputConnection.setComposingRegion: $start..$end")
        // We deliberately do not forward this to the compositor — see the
        // class doc comment for why.
        super.setComposingRegion(start, end)
        return true
    }

    override fun finishComposingText(): Boolean {
        Log.d(TAG, "InputConnection.finishComposingText")
        super.finishComposingText()
        NativeBridge.nativeFinishComposingText()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        Log.d(TAG, "InputConnection.deleteSurroundingText: before=$beforeLength after=$afterLength")
        super.deleteSurroundingText(beforeLength, afterLength)
        NativeBridge.nativeDeleteSurroundingText(beforeLength, afterLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        // Convert code points to UTF-16 code units using our Editable, then
        // delegate.
        val ed = editable ?: return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        val cursor = Selection.getSelectionStart(ed).coerceAtLeast(0)
        val before16 = utf16FromCodePoints(ed, cursor, -beforeLength)
        val after16 = utf16FromCodePoints(ed, cursor, afterLength)
        return deleteSurroundingText(before16, after16)
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

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        return super.getExtractedText(request, flags)
    }

    /**
     * Replace our Editable's contents and selection with the Wayland
     * client's authoritative state. Called from native via
     * [NativeBridge.onUpdateEditableText] after the compositor processes
     * a `set_surrounding_text` + `commit`.
     *
     * Always drops any composing span on the Editable: the client's
     * surrounding text excludes preedit, and the compositor has its own
     * preedit lifecycle on the Wayland side. Keeping a stale span here
     * would mislead a later `super.setComposingText` into replacing the
     * wrong range.
     *
     * Also calls `InputMethodManager.updateSelection` so the IME (which
     * keeps its own snapshot) stays in lockstep.
     */
    fun updateFromCompositor(text: String, selStart: Int, selEnd: Int) {
        val ed = editable ?: return
        val newSelStart = selStart.coerceIn(0, text.length)
        val newSelEnd = selEnd.coerceIn(0, text.length)
        val curText = ed.toString()
        val curSelStart = Selection.getSelectionStart(ed)
        val curSelEnd = Selection.getSelectionEnd(ed)

        BaseInputConnection.removeComposingSpans(ed)

        if (curText != text) {
            ed.replace(0, ed.length, text)
        }
        if (curSelStart != newSelStart || curSelEnd != newSelEnd) {
            Selection.setSelection(ed, newSelStart, newSelEnd)
        }

        val imm = targetView.context.getSystemService(InputMethodManager::class.java)
        // Composing region is cleared; -1, -1 tells the IME there's no preedit.
        imm?.updateSelection(targetView, newSelStart, newSelEnd, -1, -1)
    }

    private fun utf16FromCodePoints(ed: Editable, cursor: Int, codePointDelta: Int): Int {
        if (codePointDelta == 0) return 0
        val s = ed.toString()
        var idx = cursor
        if (codePointDelta > 0) {
            var remaining = codePointDelta
            while (remaining > 0 && idx < s.length) {
                val cp = s.codePointAt(idx)
                idx += Character.charCount(cp)
                remaining--
            }
            return idx - cursor
        } else {
            var remaining = -codePointDelta
            while (remaining > 0 && idx > 0) {
                val cp = s.codePointBefore(idx)
                idx -= Character.charCount(cp)
                remaining--
            }
            return cursor - idx
        }
    }

    companion object {
        private const val TAG = "tawc"
    }
}
