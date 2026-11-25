//! scrcpy-ni hazır rejimlərlə işə salmaq və işləyən sessiyaları idarə etmək.

use crate::errors;
use crate::proc;
use crate::tools::Tools;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;
use tauri::{AppHandle, Emitter};

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchOptions {
    /// standard | gaming | presentation | lowbw | camera
    pub preset: String,
    #[serde(default)]
    pub screen_off: bool,
    #[serde(default)]
    pub stay_awake: bool,
    #[serde(default)]
    pub always_on_top: bool,
    #[serde(default)]
    pub fullscreen: bool,
    #[serde(default)]
    pub no_audio: bool,
    #[serde(default)]
    pub record: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionEvent {
    pub serial: String,
    /// started | exited
    pub status: String,
    pub error: Option<String>,
    pub record_path: Option<String>,
}

pub fn preset_args(preset: &str) -> &'static [&'static str] {
    match preset {
        "gaming" => &["--max-fps=60", "--video-bit-rate=16M", "--keyboard=uhid"],
        "presentation" => &["--show-touches", "--stay-awake", "--disable-screensaver"],
        "lowbw" => &["--max-size=1024", "--video-bit-rate=3M", "--max-fps=30", "--audio-bit-rate=64K"],
        "camera" => &["--video-source=camera", "--camera-facing=back", "--no-control", "--max-size=1920"],
        _ => &[],
    }
}

pub fn build_args(serial: &str, title: &str, opts: &LaunchOptions, record_path: Option<&PathBuf>) -> Vec<String> {
    let mut args: Vec<String> = vec![
        format!("--serial={serial}"),
        format!("--window-title={title}"),
        "--no-terminal-title".into(),
    ];
    args.extend(preset_args(&opts.preset).iter().map(|s| s.to_string()));
    let camera = opts.preset == "camera";
    let has = |a: &Vec<String>, flag: &str| a.iter().any(|x| x == flag);
    if opts.screen_off && !camera {
        args.push("--turn-screen-off".into());
    }
    if opts.stay_awake && !camera && !has(&args, "--stay-awake") {
        args.push("--stay-awake".into());
    }
    if opts.always_on_top {
        args.push("--always-on-top".into());
    }
    if opts.fullscreen {
        args.push("--fullscreen".into());
    }
    if opts.no_audio {
        args.push("--no-audio".into());
    }
    if let Some(p) = record_path {
        args.push(format!("--record={}", p.display()));
    }
    args
}

struct Session {
    child: Child,
    stopped_by_user: bool,
}

#[derive(Clone, Default)]
pub struct Sessions {
    inner: Arc<Mutex<HashMap<String, Session>>>,
}

impl Sessions {
    pub fn running(&self) -> Vec<String> {
        self.inner.lock().unwrap().keys().cloned().collect()
    }

    pub fn start(
        &self,
        app: &AppHandle,
        tools: &Tools,
        serial: &str,
        title: &str,
        opts: &LaunchOptions,
        record_path: Option<PathBuf>,
    ) -> Result<(), String> {
        if self.inner.lock().unwrap().contains_key(serial) {
            return Err(crate::i18n::t("scrcpy.already_open"));
        }
        if let Some(dir) = record_path.as_ref().and_then(|p| p.parent()) {
            let _ = std::fs::create_dir_all(dir);
        }
        let args = build_args(serial, title, opts, record_path.as_ref());
        let mut cmd = proc::command(&tools.scrcpy);
        cmd.args(&args)
            .current_dir(&tools.dir)
            .env("ADB", &tools.adb)
            .env("SCRCPY_SERVER_PATH", &tools.server)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        let mut child = cmd
            .spawn()
            .map_err(|e| crate::i18n::tf("scrcpy.start_failed", &[("e", &e.to_string())]))?;

        // Son sətirləri yadda saxlayırıq ki, xəta olarsa izah edək.
        let log: Arc<Mutex<VecDeque<String>>> = Arc::new(Mutex::new(VecDeque::new()));
        for pipe in [
            child.stdout.take().map(|p| Box::new(p) as Box<dyn std::io::Read + Send>),
            child.stderr.take().map(|p| Box::new(p) as Box<dyn std::io::Read + Send>),
        ]
        .into_iter()
        .flatten()
        {
            let log = log.clone();
            thread::spawn(move || {
                for line in BufReader::new(pipe).lines().map_while(Result::ok) {
                    let mut l = log.lock().unwrap();
                    l.push_back(line);
                    if l.len() > 60 {
                        l.pop_front();
                    }
                }
            });
        }

        self.inner.lock().unwrap().insert(
            serial.to_string(),
            Session {
                child,
                stopped_by_user: false,
            },
        );
        let record_str = record_path.as_ref().map(|p| p.display().to_string());
        let _ = app.emit(
            "session",
            SessionEvent {
                serial: serial.to_string(),
                status: "started".into(),
                error: None,
                record_path: record_str.clone(),
            },
        );

        // Prosesin bitməsini izləyən axın
        let sessions = self.clone();
        let app = app.clone();
        let serial = serial.to_string();
        thread::spawn(move || loop {
            thread::sleep(Duration::from_millis(300));
            let mut map = sessions.inner.lock().unwrap();
            let Some(sess) = map.get_mut(&serial) else { return };
            let status = match sess.child.try_wait() {
                Ok(None) => continue,
                Ok(Some(s)) => Some(s),
                Err(_) => None,
            };
            let by_user = sess.stopped_by_user;
            map.remove(&serial);
            drop(map);
            // oxuyan axınlar son sətirləri yazsın
            thread::sleep(Duration::from_millis(200));
            let failed = !by_user && status.map(|s| !s.success()).unwrap_or(true);
            let error = failed.then(|| explain_log(&log.lock().unwrap()));
            let _ = app.emit(
                "session",
                SessionEvent {
                    serial: serial.clone(),
                    status: "exited".into(),
                    error,
                    record_path: record_str.clone(),
                },
            );
            return;
        });
        Ok(())
    }

    pub fn stop(&self, serial: &str) -> Result<(), String> {
        let mut map = self.inner.lock().unwrap();
        let sess = map
            .get_mut(serial)
            .ok_or_else(|| crate::i18n::t("scrcpy.not_open"))?;
        sess.stopped_by_user = true;
        sess.child
            .kill()
            .map_err(|e| crate::i18n::tf("scrcpy.stop_failed", &[("e", &e.to_string())]))
    }

    pub fn stop_all(&self) {
        let mut map = self.inner.lock().unwrap();
        for sess in map.values_mut() {
            sess.stopped_by_user = true;
            let _ = sess.child.kill();
        }
    }
}

fn explain_log(lines: &VecDeque<String>) -> String {
    // İkon xətaları zərərsizdir, istifadəçiyə göstərmirik.
    let all: Vec<&str> = lines
        .iter()
        .map(String::as_str)
        .filter(|l| !l.contains("icon"))
        .collect();
    let errors_only: Vec<&str> = all.iter().copied().filter(|l| l.contains("ERROR")).collect();
    let text = if errors_only.is_empty() { all.join("\n") } else { errors_only.join("\n") };
    if let Some(msg) = errors::explain_known(&text).or_else(|| errors::explain_known(&all.join("\n"))) {
        return msg;
    }
    let tail: Vec<&str> = all.iter().rev().take(6).rev().copied().collect();
    if tail.is_empty() {
        crate::i18n::t("scrcpy.closed_unexpected")
    } else {
        crate::i18n::tf("scrcpy.closed", &[("log", &tail.join("\n"))])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_args() {
        let opts = LaunchOptions {
            preset: "presentation".into(),
            screen_off: true,
            stay_awake: true,
            always_on_top: false,
            fullscreen: false,
            no_audio: true,
            record: false,
        };
        let a = build_args("abc", "Pixel", &opts, None);
        assert!(a.contains(&"--serial=abc".to_string()));
        assert!(a.contains(&"--turn-screen-off".to_string()));
        assert_eq!(a.iter().filter(|x| *x == "--stay-awake").count(), 1);
        assert!(a.contains(&"--no-audio".to_string()));
    }

    #[test]
    fn camera_skips_screen_off() {
        let opts = LaunchOptions {
            preset: "camera".into(),
            screen_off: true,
            stay_awake: true,
            always_on_top: false,
            fullscreen: false,
            no_audio: false,
            record: false,
        };
        let a = build_args("abc", "Pixel", &opts, None);
        assert!(!a.contains(&"--turn-screen-off".to_string()));
        assert!(!a.contains(&"--stay-awake".to_string()));
    }
}
