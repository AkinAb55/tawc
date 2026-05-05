package me.phie.tawc.dev

import android.net.LocalSocket
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One accepted broker connection. Reads the header, spawns the child
 * with [ProcessBuilder], then runs three relay threads that move bytes
 * between the socket and the child's stdio. Closing the socket from
 * the host side cancels the child via SIGKILL.
 *
 * Protocol details: notes/exec-broker.md.
 */
internal class ExecBrokerSession(private val socket: LocalSocket) {

    private data class ExecRequest(
        val argv: List<String>,
        val env: List<Pair<String, String>>?,
        val cwd: String?,
    )

    fun run() {
        val rawIn = socket.inputStream
        val sout = DataOutputStream(socket.outputStream)
        // Read the header byte-at-a-time so we don't over-buffer into the
        // first frame. After readHeader returns, the next byte on rawIn
        // is the start of the first frame.
        val req = try {
            readHeader(rawIn)
        } catch (t: Throwable) {
            sendErrorAndExit(sout, "header parse: ${t.message}")
            return
        }
        val sin = DataInputStream(rawIn)
        Log.i(ExecBroker.TAG, "exec argv=${req.argv} cwd=${req.cwd}")

        val pb = ProcessBuilder(req.argv)
            .redirectErrorStream(false)
        // Always clear and rebuild env from the header — never leak the
        // broker's own (app-process) environment to children. Callers
        // pass every var they want explicitly. Matches the protocol
        // doc's "ENV replaces, doesn't merge" guarantee.
        pb.environment().clear()
        if (req.env != null) {
            for ((k, v) in req.env) pb.environment()[k] = v
        }
        if (req.cwd != null) pb.directory(File(req.cwd))

        val proc: Process = try {
            pb.start()
        } catch (t: Throwable) {
            sendErrorAndExit(sout, "spawn: ${t.message}")
            return
        }

        val socketAlive = AtomicBoolean(true)

        // Pump child stdout / stderr → socket. Both threads are
        // daemons so they won't keep the JVM alive on shutdown.
        val outT = thread(name = "tawc-exec-stdout", isDaemon = true) {
            relayChildOutToSocket(proc.inputStream, STREAM_STDOUT, sout, socketAlive)
        }
        val errT = thread(name = "tawc-exec-stderr", isDaemon = true) {
            relayChildOutToSocket(proc.errorStream, STREAM_STDERR, sout, socketAlive)
        }

        // Two events race: socket EOF (host disconnected) or child
        // exit (work done). A latch lets the main thread wait for
        // whichever fires first; the loser is allowed to keep
        // running — its cleanup happens implicitly when we close the
        // socket / reap the child below.
        val finished = CountDownLatch(1)
        val hostDisconnected = AtomicBoolean(false)

        thread(name = "tawc-exec-stdin", isDaemon = true) {
            val cancelled = try {
                relaySocketToChildIn(sin, proc.outputStream)
            } catch (t: Throwable) {
                Log.w(ExecBroker.TAG, "socket→stdin error", t); true
            }
            if (cancelled) {
                hostDisconnected.set(true)
                finished.countDown()
            }
        }
        thread(name = "tawc-exec-waiter", isDaemon = true) {
            try { proc.waitFor() } catch (_: InterruptedException) {}
            finished.countDown()
        }
        finished.await()

        if (hostDisconnected.get() && proc.isAlive) {
            // destroyForcibly() SIGKILLs the immediate child, but its
            // descendants get reparented to init and survive — bash
            // running `sleep 600`, etc. Snapshot the process tree
            // before the kill (walk /proc/*/status looking for matching
            // PPid: lines, see [collectDescendants]), then SIGKILL each
            // captured pid by hand. Pids can in theory be reused across
            // the kill window, but on Android pid_max is 32k+ and this
            // is dev tooling.
            val rootPid = pidOf(proc)
            val descendants = if (rootPid > 0) collectDescendants(rootPid) else emptyList()
            Log.i(ExecBroker.TAG, "cancel rootPid=$rootPid descendants=$descendants")
            proc.destroyForcibly()
            for (p in descendants) {
                try { Os.kill(p, OsConstants.SIGKILL) } catch (_: Throwable) {}
            }
        }

        val exit = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
        outT.join(2000)
        errT.join(2000)

        // Take the same lock as the stdout/stderr relays so we can't
        // interleave with a slow relay that hasn't finished its last
        // synchronized(sout) block yet (outT.join() can time out).
        try {
            synchronized(sout) {
                sout.write(STREAM_EXIT)
                sout.writeInt(4)
                sout.writeInt(exit)
                sout.flush()
            }
        } catch (_: IOException) {
            // Host already disconnected — nothing to do.
        }
        socketAlive.set(false)
    }

    private fun sendErrorAndExit(sout: DataOutputStream, msg: String) {
        try {
            val bytes = msg.toByteArray(Charsets.UTF_8)
            sout.write(STREAM_ERR)
            sout.writeInt(bytes.size)
            sout.write(bytes)
            sout.write(STREAM_EXIT)
            sout.writeInt(4)
            sout.writeInt(-1)
            sout.flush()
        } catch (_: IOException) {}
    }

    /**
     * Read the LF-terminated header until an empty line. Reads
     * byte-at-a-time so the next byte on [stream] is the start of the
     * first binary frame — using BufferedReader here would silently
     * absorb frame bytes into its internal buffer.
     */
    private fun readHeader(stream: InputStream): ExecRequest {
        val firstLine = readHeaderLine(stream)
            ?: throw IOException("connection closed before header")
        if (firstLine != "TAWCEXEC 1") {
            throw IOException("bad magic: '$firstLine'")
        }
        val argv = mutableListOf<String>()
        var env: MutableList<Pair<String, String>>? = null
        var cwd: String? = null
        while (true) {
            val line = readHeaderLine(stream) ?: throw IOException("EOF in header")
            if (line.isEmpty()) break
            val sp = line.indexOf(' ')
            val key = if (sp < 0) line else line.substring(0, sp)
            val value = if (sp < 0) "" else line.substring(sp + 1)
            when (key) {
                "ARGV" -> argv += value
                "ENV"  -> {
                    if (env == null) env = mutableListOf()
                    val eq = value.indexOf('=')
                    if (eq < 0) throw IOException("malformed ENV: '$value'")
                    env += value.substring(0, eq) to value.substring(eq + 1)
                }
                "CWD"  -> cwd = value
                else   -> throw IOException("unknown header key: '$key'")
            }
        }
        if (argv.isEmpty()) throw IOException("no ARGV in header")
        return ExecRequest(argv, env, cwd)
    }

    /**
     * Extract the kernel pid of [proc]. `Process.pid()` is the public
     * Java 9+ API but it isn't exposed at the Android API surface we
     * compile against (verified: `proc.pid()` doesn't resolve on
     * compileSdk=34 / minSdk=29). The underlying `ProcessImpl` /
     * `UNIXProcess` carries a `pid` int field we can pluck off via
     * reflection. Returns -1 on any failure.
     */
    private fun pidOf(proc: Process): Int {
        return try {
            val f = proc.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(proc)
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Collect pids of every descendant of [rootPid] by scanning each
     * `/proc/<pid>/status` for matching `PPid:` lines. Built bottom-up:
     * walk the entire ppid map once, then BFS from root. Returns
     * leaves-first so callers can SIGKILL in topological order.
     *
     * Why not `/proc/<pid>/task/<pid>/children`? That requires kernel
     * `CONFIG_PROC_CHILDREN`, which Android's emulator (and many real
     * devices) ship without — the file doesn't exist.
     */
    private fun collectDescendants(rootPid: Int): List<Int> {
        // Build pid → list of children once.
        val children = HashMap<Int, MutableList<Int>>()
        val procDir = File("/proc")
        val entries = procDir.list() ?: return emptyList()
        for (name in entries) {
            val pid = name.toIntOrNull() ?: continue
            val ppid = readPpid(pid) ?: continue
            children.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val out = mutableListOf<Int>()
        fun visit(pid: Int) {
            val kids = children[pid] ?: return
            for (k in kids) visit(k)
            out += kids
        }
        visit(rootPid)
        return out
    }

    private fun readPpid(pid: Int): Int? {
        val f = File("/proc/$pid/status")
        return try {
            f.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("PPid:")) {
                        return@useLines line.substringAfter(':').trim().toIntOrNull()
                    }
                }
                null
            }
        } catch (_: Throwable) { null }
    }

    /**
     * Read a UTF-8, LF-terminated line. Returns null on stream EOF.
     * Capped at MAX_FRAME bytes so a peer can't OOM the JVM by
     * streaming an LF-less header forever.
     */
    private fun readHeaderLine(stream: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = stream.read()
            if (b == -1) return if (buf.size() == 0) null
                                else buf.toByteArray().toString(Charsets.UTF_8)
            if (b == '\n'.code) return buf.toByteArray().toString(Charsets.UTF_8)
            if (buf.size() >= MAX_FRAME) {
                throw IOException("header line exceeded $MAX_FRAME bytes")
            }
            buf.write(b)
        }
    }

    /**
     * Read frames from the socket until socket EOF, forwarding stdin
     * frames to the child and closing child stdin on a stdin EOF
     * frame. **Keeps reading after the stdin EOF frame** so a host
     * disconnect (Ctrl-C / SIGKILL of `tawc-exec`) is observable even
     * for commands that never read stdin — without this, every test
     * that backgrounds tawc-exec would leak orphans because the host
     * helper sends stdin EOF immediately when /dev/null is the local
     * stdin, and then the broker would stop watching for cancellation.
     *
     * Returns true if we exited because the socket dropped (cancel
     * the child); false on protocol error (also cancel, but logged
     * differently).
     */
    private fun relaySocketToChildIn(sin: DataInputStream, childIn: OutputStream): Boolean {
        var stdinClosed = false
        try {
            while (true) {
                val streamId = sin.read()
                if (streamId == -1) return true
                val len = sin.readInt()
                when (streamId) {
                    STREAM_STDIN -> {
                        if (len <= 0 || len > MAX_FRAME) {
                            throw IOException("bad stdin frame length $len")
                        }
                        val buf = ByteArray(len)
                        sin.readFully(buf)
                        if (!stdinClosed) {
                            try { childIn.write(buf); childIn.flush() }
                            catch (_: IOException) { stdinClosed = true }
                        }
                    }
                    STREAM_STDIN_EOF -> {
                        if (len != 0) throw IOException("stdin EOF frame has len $len")
                        if (!stdinClosed) {
                            try { childIn.close() } catch (_: IOException) {}
                            stdinClosed = true
                        }
                        // Continue reading so we still observe socket EOF.
                    }
                    else -> throw IOException("unexpected client stream $streamId")
                }
            }
        } catch (e: IOException) {
            if (e is java.io.EOFException) return true
            Log.w(ExecBroker.TAG, "socket→stdin: ${e.message}")
            return true
        }
    }

    /**
     * Read child stdout/stderr in 4 KB chunks; emit one frame per read.
     * Stops on stream EOF (child closed) or on socket I/O error. Frame
     * writes are synchronized on [sout] so stdout / stderr / exit don't
     * interleave at the byte level.
     */
    private fun relayChildOutToSocket(
        from: InputStream,
        streamId: Int,
        sout: DataOutputStream,
        socketAlive: AtomicBoolean,
    ) {
        val buf = ByteArray(4096)
        while (socketAlive.get()) {
            val n = try { from.read(buf) } catch (_: IOException) { -1 }
            if (n <= 0) break
            synchronized(sout) {
                try {
                    sout.write(streamId)
                    sout.writeInt(n)
                    sout.write(buf, 0, n)
                    sout.flush()
                } catch (_: IOException) {
                    socketAlive.set(false)
                    return
                }
            }
        }
    }

    companion object {
        // Stream IDs — kept in sync with notes/exec-broker.md and
        // tools/tawc-exec/src/main.rs.
        const val STREAM_STDIN = 0
        const val STREAM_STDOUT = 1
        const val STREAM_STDERR = 2
        const val STREAM_EXIT = 3
        const val STREAM_STDIN_EOF = 4
        const val STREAM_ERR = 5
        const val MAX_FRAME = 1 shl 20  // 1 MB safety cap
    }
}
