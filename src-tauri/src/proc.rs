//! Xarici prosesləri (adb, scrcpy) konsol pəncərəsi açmadan və timeout ilə işə salmaq.

use std::io::Read;
use std::path::Path;
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

/// Windows-da qara konsol pəncərəsi açılmasın deyə CREATE_NO_WINDOW bayrağı ilə Command yaradır.
pub fn command(program: &Path) -> Command {
    #[allow(unused_mut)]
    let mut cmd = Command::new(program);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}

pub struct Output {
    pub success: bool,
    pub stdout: String,
    pub stderr: String,
}

impl Output {
    /// stdout və stderr birlikdə (adb bir çox mesajı stderr-ə yazır).
    pub fn combined(&self) -> String {
        let mut s = self.stdout.trim().to_string();
        let e = self.stderr.trim();
        if !e.is_empty() {
            if !s.is_empty() {
                s.push('\n');
            }
            s.push_str(e);
        }
        s
    }
}

/// Əmri işə salır, `timeout` keçərsə prosesi öldürür.
pub fn run_with_timeout(mut cmd: Command, timeout: Duration) -> Result<Output, String> {
    cmd.stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = cmd
        .spawn()
        .map_err(|e| crate::i18n::tf("proc.spawn_failed", &[("e", &e.to_string())]))?;

    let mut out_pipe = child.stdout.take().expect("stdout piped");
    let mut err_pipe = child.stderr.take().expect("stderr piped");
    let out_t = thread::spawn(move || {
        let mut buf = Vec::new();
        let _ = out_pipe.read_to_end(&mut buf);
        String::from_utf8_lossy(&buf).into_owned()
    });
    let err_t = thread::spawn(move || {
        let mut buf = Vec::new();
        let _ = err_pipe.read_to_end(&mut buf);
        String::from_utf8_lossy(&buf).into_owned()
    });

    let start = Instant::now();
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if start.elapsed() >= timeout => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(crate::i18n::t("proc.timeout"));
            }
            Ok(None) => thread::sleep(Duration::from_millis(50)),
            Err(e) => return Err(crate::i18n::tf("proc.wait_failed", &[("e", &e.to_string())])),
        }
    };

    Ok(Output {
        success: status.success(),
        stdout: out_t.join().unwrap_or_default(),
        stderr: err_t.join().unwrap_or_default(),
    })
}
