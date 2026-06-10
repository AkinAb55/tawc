# focus-activity dev action reports success when app is backgrounded

`focus-activity` (InputActions.kt) calls `startActivity` from the app
context. When the tawc app has no visible window, Android 10+ blocks the
background activity start: nothing comes to the foreground, but
`startActivity` does not throw, so the action returns rc 0 and the caller
thinks the switch happened.

Observed while verifying the clipboard focus-gain sync: with Firefox
foreground, `tawc-exec --action focus-activity --arg activityId=...`
returned success but Firefox stayed resumed. Workaround used there:
`su -c 'am start -n me.phie.tawc/.compositor.CompositorActivity -a
android.intent.action.VIEW -d tawc://activity/<id>'` (root can launch the
non-exported activity directly).

Tests currently only call focus-activity while a tawc activity is already
foreground (switching between compositor windows), so nothing is broken
today.

Possible fixes: verify post-start that the target activity actually
resumed (e.g. poll `getActivity(...)`/lifecycle state and fail otherwise),
or document the foreground-only contract in notes/exec-broker.md.
