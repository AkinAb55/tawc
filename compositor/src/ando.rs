//! ando broker — lets rootfs guests run plain Android commands
//! (`ando <cmd>`: no tawcroot seccomp filter, no guest env, stdio on
//! the caller's own fds via SCM_RIGHTS). Guest client: `tawcroot/ando/`.
//! Protocol, security model, and design rationale: notes/ando.md.
//!
//! A standalone thread, deliberately not part of the compositor event
//! loop — alive whenever the app process is. Implemented in Rust
//! rather than Kotlin because the data path is unix-syscall-shaped
//! (peercred, SCM_RIGHTS, setpgid/fchdir, waitid, kill), which
//! Kotlin's LocalSocket handles poorly.

use std::io::{self, BufRead, BufReader, Write};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

/// PATH given to children when the app process env carries none.
const FALLBACK_PATH: &str =
    "/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin";

const MAX_LINE: usize = 65536;
const MAX_HEADER_LINES: usize = 4096;

static STARTED: AtomicBool = AtomicBool::new(false);

/// Spawn the broker listener thread. Idempotent per process.
///
/// `socket_path` is host-side (`<appData>/share/ando.sock`); being
/// per-app-data-dir makes it naturally multi-user-safe, and the share
/// bind gives every rootfs the fixed guest view
/// `/usr/share/tawc/ando.sock` — no name computation anywhere.
pub fn start(socket_path: String) {
    if STARTED.swap(true, Ordering::SeqCst) {
        return;
    }
    let res = std::thread::Builder::new()
        .name("ando-listen".into())
        .spawn(move || run(socket_path));
    if let Err(e) = res {
        log::error!("ando: failed to spawn listener thread: {}", e);
        STARTED.store(false, Ordering::SeqCst);
    }
}

fn run(socket_path: String) {
    // Clear the stale node from a previous app process — bind refuses
    // to reuse it. Nothing else can own this path (app-private dir).
    let _ = std::fs::remove_file(&socket_path);
    let listener = match UnixListener::bind(&socket_path) {
        Ok(l) => l,
        Err(e) => {
            log::error!("ando: bind {}: {}", socket_path, e);
            return;
        }
    };
    log::info!("ando: listening on {}", socket_path);
    for conn in listener.incoming() {
        let stream = match conn {
            Ok(s) => s,
            Err(e) => {
                log::warn!("ando: accept: {}", e);
                continue;
            }
        };
        // Per-connection thread; body wrapped in catch_unwind so a
        // parsing bug kills the connection, not the app (the panic
        // hook in lib.rs exempts ando-* threads from its abort).
        let res = std::thread::Builder::new().name("ando-conn".into()).spawn(move || {
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                handle_connection(stream)
            }));
            match r {
                Ok(Ok(())) => {}
                Ok(Err(e)) => log::warn!("ando: connection: {}", e),
                Err(_) => log::warn!("ando: connection handler panicked"),
            }
        });
        if let Err(e) = res {
            log::warn!("ando: failed to spawn connection thread: {}", e);
        }
    }
}

struct Request {
    argv: Vec<String>,
    env: Vec<(String, String)>,
}

fn proto_err(msg: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.into())
}

fn handle_connection(stream: UnixStream) -> io::Result<()> {
    let uid = peer_uid(&stream)?;
    let my_uid = unsafe { libc::getuid() };
    if uid != my_uid {
        return Err(proto_err(format!("rejected peer uid {} (want {})", uid, my_uid)));
    }

    // The fd message is the very first byte on the wire, so everything
    // after it can go through one BufReader without risking a buffered
    // read swallowing the SCM_RIGHTS-bearing byte (ancillary data is
    // dropped when its byte is consumed by a plain read).
    let [stdin_fd, stdout_fd, stderr_fd, cwd_fd] = recv_fds(&stream)?;
    let mut reader = BufReader::new(stream);
    let req = read_header(&mut reader)?;

    // Kept out of the Command so spawn failures can still reach the
    // guest's stderr.
    let stderr_copy = stderr_fd.try_clone()?;

    let mut cmd = Command::new(&req.argv[0]);
    cmd.args(&req.argv[1..])
        .envs(req.env.iter().map(|(k, v)| (k, v)))
        .stdin(Stdio::from(stdin_fd))
        .stdout(Stdio::from(stdout_fd))
        .stderr(Stdio::from(stderr_fd));
    // Child env = the app process's own (zygote PATH, ANDROID_ROOT, …)
    // plus client extras; nothing from the guest env leaks unless
    // explicitly forwarded. PATH also steers Command's child-side
    // program search (std sets the child environ before execvp).
    if std::env::var_os("PATH").is_none() && !req.env.iter().any(|(k, _)| k == "PATH") {
        cmd.env("PATH", FALLBACK_PATH);
    }
    let cwd_raw = cwd_fd.as_raw_fd();
    unsafe {
        // setpgid(0,0): the child leads its own group so kill(-pgid, …)
        // reaps the whole tree. fchdir: start where the caller stands
        // (notes/ando.md "cwd as an fd"); on failure stay in the app
        // process's cwd. std's fork path already resets the signal
        // mask and SIGPIPE disposition.
        cmd.pre_exec(move || {
            libc::setpgid(0, 0);
            libc::fchdir(cwd_raw);
            Ok(())
        });
    }
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            // execvp reports EACCES, not ENOENT, for a missing program
            // when any unsearchable dir sits on the app PATH — probe
            // existence ourselves to tell "not found" (127) from a
            // real spawn failure (126).
            let not_found =
                e.kind() == io::ErrorKind::NotFound || !exists_on_path(&req.argv[0], &req.env);
            let msg = if not_found {
                format!("ando: {}: not found\n", req.argv[0])
            } else {
                format!("ando: {}: spawn failed: {}\n", req.argv[0], e)
            };
            let _ = unsafe {
                libc::write(stderr_copy.as_raw_fd(), msg.as_ptr() as *const libc::c_void, msg.len())
            };
            let _ = writeln!(reader.get_mut(), "EXIT {}", if not_found { 127 } else { 126 });
            return Ok(());
        }
    };
    drop(stderr_copy);
    drop(cwd_fd);
    let pid = child.id() as libc::pid_t;
    // Parent half of the setpgid race: harmless EACCES once the child
    // has exec'd (it already did its own setpgid by then).
    unsafe { libc::setpgid(pid, pid) };

    // Waiter observes the exit WITHOUT reaping (WNOWAIT), so the
    // pid/pgid stays reserved by the zombie until the final reap below
    // — kill(-pid, …) can never hit a recycled process group.
    let exited = Arc::new(AtomicBool::new(false));
    let mut writer = reader.get_ref().try_clone()?;
    {
        let exited = exited.clone();
        std::thread::Builder::new()
            .name("ando-wait".into())
            .spawn(move || {
                let code = await_exit_nowait(pid);
                exited.store(true, Ordering::SeqCst);
                if let Some(code) = code {
                    let _ = writeln!(writer, "EXIT {}", code);
                }
                let _ = writer.shutdown(std::net::Shutdown::Both);
            })
            .map_err(|e| proto_err(format!("spawn waiter: {}", e)))?;
    }

    // Forward SIG lines until the client goes away (waiter shutdown or
    // real disconnect).
    let mut line = String::new();
    loop {
        line.clear();
        match reader.read_line(&mut line) {
            Ok(0) | Err(_) => break,
            Ok(_) => {
                if let Some(n) = line.trim_end().strip_prefix("SIG ").and_then(|s| s.parse::<i32>().ok()) {
                    if (1..=64).contains(&n) {
                        unsafe { libc::kill(-pid, n) };
                    }
                }
            }
        }
    }
    if !exited.load(Ordering::SeqCst) {
        // Client died before the child: same orphan-prevention contract
        // as the debug broker. The uid-wide kill on app death remains
        // the backstop for double-fork escapees.
        unsafe { libc::kill(-pid, libc::SIGKILL) };
    }
    let _ = child.wait();
    Ok(())
}

/// Does `prog` resolve to anything, against the same PATH the child
/// would search (client extras > app env > fallback)? Error-path only.
fn exists_on_path(prog: &str, extras: &[(String, String)]) -> bool {
    if prog.contains('/') {
        return std::path::Path::new(prog).exists();
    }
    let path = extras
        .iter()
        .rev()
        .find(|(k, _)| k == "PATH")
        .map(|(_, v)| v.into())
        .or_else(|| std::env::var_os("PATH"))
        .unwrap_or_else(|| FALLBACK_PATH.into());
    std::env::split_paths(&path).any(|d| d.join(prog).exists())
}

fn peer_uid(stream: &UnixStream) -> io::Result<libc::uid_t> {
    let mut cred: libc::ucred = unsafe { std::mem::zeroed() };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut cred as *mut _ as *mut libc::c_void,
            &mut len,
        )
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(cred.uid)
}

/// Wire-compatible with the ExecBroker value encoding
/// (notes/exec-broker.md "Value encoding"): `\\`→`\`, `\n`→LF, `\r`→CR.
fn decode_value(s: &str) -> String {
    if !s.contains('\\') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars().peekable();
    while let Some(c) = it.next() {
        if c == '\\' {
            match it.peek() {
                Some('\\') => {
                    it.next();
                    out.push('\\');
                }
                Some('n') => {
                    it.next();
                    out.push('\n');
                }
                Some('r') => {
                    it.next();
                    out.push('\r');
                }
                _ => out.push(c),
            }
        } else {
            out.push(c);
        }
    }
    out
}

fn read_header(reader: &mut BufReader<UnixStream>) -> io::Result<Request> {
    let mut line = String::new();
    let mut read_line = |line: &mut String| -> io::Result<()> {
        line.clear();
        if reader.read_line(line)? == 0 {
            return Err(proto_err("eof in header"));
        }
        if line.len() > MAX_LINE {
            return Err(proto_err("header line too long"));
        }
        while line.ends_with('\n') || line.ends_with('\r') {
            line.pop();
        }
        Ok(())
    };
    read_line(&mut line)?;
    if line != "TAWCANDO 1" {
        return Err(proto_err(format!("bad magic {:?}", line)));
    }
    let mut argv = Vec::new();
    let mut env = Vec::new();
    for _ in 0..MAX_HEADER_LINES {
        read_line(&mut line)?;
        if line.is_empty() {
            if argv.is_empty() {
                return Err(proto_err("no ARGV"));
            }
            return Ok(Request { argv, env });
        }
        if let Some(v) = line.strip_prefix("ARGV ") {
            argv.push(decode_value(v));
        } else if let Some(kv) = line.strip_prefix("ENV ") {
            let Some((k, v)) = kv.split_once('=') else {
                return Err(proto_err("ENV without ="));
            };
            env.push((k.to_string(), decode_value(v)));
        } else {
            return Err(proto_err(format!("bad header line {:?}", line)));
        }
    }
    Err(proto_err("header too long"))
}

/// Receive the protocol byte whose SCM_RIGHTS payload must be exactly
/// 4 fds: stdin, stdout, stderr, cwd (O_PATH directory).
fn recv_fds(stream: &UnixStream) -> io::Result<[OwnedFd; 4]> {
    let mut data = [0u8; 1];
    let mut iov = libc::iovec {
        iov_base: data.as_mut_ptr() as *mut libc::c_void,
        iov_len: 1,
    };
    // 8-aligned cmsg buffer with room for well over 4 fds.
    let mut cmsgbuf = [0u64; 16];
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsgbuf.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = std::mem::size_of_val(&cmsgbuf) as _;

    let n = unsafe { libc::recvmsg(stream.as_raw_fd(), &mut msg, libc::MSG_CMSG_CLOEXEC) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if n == 0 {
        return Err(proto_err("eof before fd message"));
    }

    let mut fds: Vec<OwnedFd> = Vec::new();
    let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    while !cmsg.is_null() {
        let hdr = unsafe { &*cmsg };
        if hdr.cmsg_level == libc::SOL_SOCKET && hdr.cmsg_type == libc::SCM_RIGHTS {
            let data_len = hdr.cmsg_len as usize - unsafe { libc::CMSG_LEN(0) } as usize;
            let count = data_len / std::mem::size_of::<RawFd>();
            let data_ptr = unsafe { libc::CMSG_DATA(cmsg) } as *const RawFd;
            for i in 0..count {
                let fd = unsafe { std::ptr::read_unaligned(data_ptr.add(i)) };
                fds.push(unsafe { OwnedFd::from_raw_fd(fd) });
            }
        }
        cmsg = unsafe { libc::CMSG_NXTHDR(&msg, cmsg) };
    }
    // Checked after collection: the kernel installs whatever fds fit
    // even when it truncates, and collected OwnedFds close on drop.
    if msg.msg_flags & libc::MSG_CTRUNC != 0 {
        return Err(proto_err("fd message control data truncated"));
    }
    <[OwnedFd; 4]>::try_from(fds).map_err(|got| proto_err(format!("expected 4 fds, got {}", got.len())))
}

/// Block until the child exits, without reaping it (WNOWAIT — see the
/// call site for why). Returns the client-facing exit code (128+sig
/// for signal deaths, mirroring shells), or None if the child was
/// already reaped by the disconnect path racing us (client is gone;
/// nobody is waiting for an EXIT line).
fn await_exit_nowait(pid: libc::pid_t) -> Option<i32> {
    let mut info: libc::siginfo_t = unsafe { std::mem::zeroed() };
    loop {
        let r = unsafe {
            libc::waitid(libc::P_PID, pid as libc::id_t, &mut info, libc::WEXITED | libc::WNOWAIT)
        };
        if r == 0 {
            break;
        }
        if io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
            continue;
        }
        return None;
    }
    let status = unsafe { info.si_status() };
    Some(match info.si_code {
        libc::CLD_EXITED => status,
        _ => 128 + status, // CLD_KILLED / CLD_DUMPED: si_status is the signal
    })
}
