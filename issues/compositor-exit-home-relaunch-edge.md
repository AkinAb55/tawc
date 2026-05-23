# Compositor Exit can leave an already-visible home screen stopped

After tapping the persistent notification's `Exit` action, the
compositor service stops and the notification disappears as expected.
If `MainActivity` is still the top activity, a subsequent launcher-style
start can be delivered to that existing instance without recreating it:

```
adb shell am start -n me.phie.tawc/.MainActivity
# Warning: Activity not started, intent has been delivered to currently running top-most instance.
```

In that state `MainActivity.onCreate` is not reached, so the compositor
service is not restarted. This needs a deliberate app-level lifecycle
decision rather than an `onNewIntent` workaround: after a user-requested
Exit, should the home screen show a stopped compositor state with a
Start action, or should returning to/using home automatically restart it?

## Repro

1. Launch TAWC with no Linux windows open.
2. Expand notifications and tap the compositor notification's `Exit`
   action.
3. Confirm the `TAWC running` notification is gone.
4. Run `adb shell am start -n me.phie.tawc/.MainActivity`.
5. The existing home Activity is reused and the compositor notification
   does not come back.

## Possible Fix

Add an explicit app-visible compositor lifecycle state and a home-screen
restart affordance, then make launcher/home/run actions use that model.
