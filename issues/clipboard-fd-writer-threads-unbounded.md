# clipboard fd-writer threads are unbounded and can block forever

`clipboard::write_text_to_fd` (X11→client text) and
`clipboard::write_android_clipboard_to_fd` (paste-time Android fetch)
spawn one detached thread per request and `write_all` into a
client-supplied pipe with no timeout. A client that requests a paste
and never reads its pipe pins a thread + fd for the life of the
process, and can do so repeatedly.

Not urgent: clients are local and already trusted with far worse, and
the eager pull direction (the path a *hostile* source controls) does
have a 5s timeout and byte cap. But the writer side deserves either a
write timeout (poll + deadline like the pull path) or a small shared
writer with cancellation, eventually.

Also worth folding in if touched: the paste fetch thread blocks on a
reverse-JNI binder call to ClipboardManager with no deadline of its own.
