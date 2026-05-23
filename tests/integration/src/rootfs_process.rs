use std::io;
use std::process::ExitStatus;
use std::time::Duration;

use crate::exec_broker::{BrokerChild, BrokerPipe};
use crate::{adb, GraphicsBackend};

const STOP_TIMEOUT: Duration = Duration::from_secs(5);

/// A process running inside the Android rootfs through the app-side broker.
///
/// Per-test isolation is handled by the broker `test-init` action, which asks
/// the app to disconnect compositor clients and run `ProcessScanner` inside
/// the app process. This handle is only a convenience for mid-test lifecycle
/// control and stdout/stderr capture; it deliberately avoids host-side
/// pidfiles, `/proc` PGID reads, `ps`, and `kill -- -PGID`.
pub struct RootfsProcess {
    child: BrokerChild,
    exit_status: Option<ExitStatus>,
    stopped: bool,
}

impl RootfsProcess {
    /// Spawn a command in the rootfs using the user's persisted graphics
    /// backend setting.
    pub fn spawn(cmd: &str) -> io::Result<Self> {
        Self::spawn_inner(None, cmd)
    }

    /// Backend-pinned variant of [Self::spawn].
    pub fn spawn_with(backend: GraphicsBackend, cmd: &str) -> io::Result<Self> {
        Self::spawn_inner(Some(backend), cmd)
    }

    fn spawn_inner(backend: Option<GraphicsBackend>, cmd: &str) -> io::Result<Self> {
        let child = match backend {
            Some(b) => adb::rootfs_spawn_with(b, cmd)?,
            None => adb::rootfs_spawn(cmd)?,
        };
        Ok(Self {
            child,
            exit_status: None,
            stopped: false,
        })
    }

    /// Historical no-op retained for call sites that start draining stdout
    /// before waiting for the app to render. The app-side broker owns process
    /// grouping now; tests no longer discover host PIDs or PGIDs.
    pub fn ensure_pgid(&mut self) {}

    /// Take stdout from the underlying broker session (can only be called once).
    pub fn take_stdout(&mut self) -> Option<BrokerPipe> {
        self.child.take_stdout()
    }

    /// Take stderr from the underlying broker session (can only be called once).
    pub fn take_stderr(&mut self) -> Option<BrokerPipe> {
        self.child.take_stderr()
    }

    /// Check whether the broker session has reported process exit.
    pub fn is_running(&mut self) -> bool {
        if self.exit_status.is_some() {
            return false;
        }
        match self.child.try_wait() {
            Ok(Some(status)) => {
                self.exit_status = Some(status);
                false
            }
            Ok(None) => true,
            Err(_) => false,
        }
    }

    /// Stop the process by closing the broker session and waiting for it to
    /// finish. Returns an error when the process had already exited before the
    /// explicit stop, preserving the old test signal for unexpected crashes.
    ///
    /// This intentionally does not run the broad app-side rootfs cleanup sweep:
    /// some tests keep multiple rootfs clients alive at once. The isolation
    /// boundaries (`test_init` and `assert_compositor_clean`) own that sweep.
    pub fn stop(&mut self) -> Result<(), String> {
        self.stopped = true;
        let already_gone = !self.is_running();
        let _ = self.child.kill();
        match self.child.wait_timeout(STOP_TIMEOUT) {
            Ok(Some(status)) => {
                self.exit_status.get_or_insert(status);
            }
            Ok(None) => {
                return Err(format!(
                    "broker session did not stop within {:?}",
                    STOP_TIMEOUT
                ));
            }
            Err(e) if self.exit_status.is_none() => return Err(e.to_string()),
            Err(_) => {}
        }
        if already_gone {
            return Err("Process exited/crashed before being stopped".to_string());
        }
        Ok(())
    }
}

impl Drop for RootfsProcess {
    fn drop(&mut self) {
        if !self.stopped {
            let _ = self.child.kill();
            let _ = self.child.wait_timeout(STOP_TIMEOUT);
        }
    }
}
