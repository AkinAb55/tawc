#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

compile_sdk="$(
  sed -nE 's/^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts |
    head -n1
)"
if [[ -z "$compile_sdk" ]]; then
  echo "check-inputconnection-audit: could not read compileSdk" >&2
  exit 1
fi

local_sdk="$(
  sed -nE 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*(.*[^[:space:]])[[:space:]]*$/\1/p' local.properties 2>/dev/null |
    head -n1
)"
sdk_root="${local_sdk:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}}"
android_jar="$sdk_root/platforms/android-$compile_sdk/android.jar"
if [[ ! -f "$android_jar" ]]; then
  echo "check-inputconnection-audit: missing $android_jar" >&2
  echo "Set sdk.dir in local.properties or export ANDROID_HOME/ANDROID_SDK_ROOT." >&2
  exit 1
fi

actual="$(
  javap -classpath "$android_jar" android.view.inputmethod.InputConnection |
    sed -nE 's/^  (public .*\);)$/\1/p'
)"

expected="$(cat <<'EOF'
public abstract boolean beginBatchEdit();
public abstract boolean clearMetaKeyStates(int);
public abstract void closeConnection();
public abstract boolean commitCompletion(android.view.inputmethod.CompletionInfo);
public abstract boolean commitContent(android.view.inputmethod.InputContentInfo, int, android.os.Bundle);
public abstract boolean commitCorrection(android.view.inputmethod.CorrectionInfo);
public abstract boolean commitText(java.lang.CharSequence, int);
public default boolean commitText(java.lang.CharSequence, int, android.view.inputmethod.TextAttribute);
public abstract boolean deleteSurroundingText(int, int);
public abstract boolean deleteSurroundingTextInCodePoints(int, int);
public abstract boolean endBatchEdit();
public abstract boolean finishComposingText();
public abstract int getCursorCapsMode(int);
public abstract android.view.inputmethod.ExtractedText getExtractedText(android.view.inputmethod.ExtractedTextRequest, int);
public abstract android.os.Handler getHandler();
public abstract java.lang.CharSequence getSelectedText(int);
public default android.view.inputmethod.SurroundingText getSurroundingText(int, int, int);
public abstract java.lang.CharSequence getTextAfterCursor(int, int);
public abstract java.lang.CharSequence getTextBeforeCursor(int, int);
public abstract boolean performContextMenuAction(int);
public abstract boolean performEditorAction(int);
public default void performHandwritingGesture(android.view.inputmethod.HandwritingGesture, java.util.concurrent.Executor, java.util.function.IntConsumer);
public abstract boolean performPrivateCommand(java.lang.String, android.os.Bundle);
public default boolean performSpellCheck();
public default boolean previewHandwritingGesture(android.view.inputmethod.PreviewableHandwritingGesture, android.os.CancellationSignal);
public default boolean replaceText(int, int, java.lang.CharSequence, int, android.view.inputmethod.TextAttribute);
public abstract boolean reportFullscreenMode(boolean);
public abstract boolean requestCursorUpdates(int);
public default boolean requestCursorUpdates(int, int);
public default void requestTextBoundsInfo(android.graphics.RectF, java.util.concurrent.Executor, java.util.function.Consumer<android.view.inputmethod.TextBoundsInfoResult>);
public abstract boolean sendKeyEvent(android.view.KeyEvent);
public abstract boolean setComposingRegion(int, int);
public default boolean setComposingRegion(int, int, android.view.inputmethod.TextAttribute);
public abstract boolean setComposingText(java.lang.CharSequence, int);
public default boolean setComposingText(java.lang.CharSequence, int, android.view.inputmethod.TextAttribute);
public default boolean setImeConsumesInput(boolean);
public abstract boolean setSelection(int, int);
public default android.view.inputmethod.TextSnapshot takeSnapshot();
EOF
)"

if [[ "$actual" != "$expected" ]]; then
  echo "InputConnection API surface changed for compileSdk=$compile_sdk." >&2
  echo "Update notes/text-input.md and scripts/check-inputconnection-audit.sh together." >&2
  diff -u <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") >&2 || true
  exit 1
fi

echo "InputConnection audit matches compileSdk=$compile_sdk"
