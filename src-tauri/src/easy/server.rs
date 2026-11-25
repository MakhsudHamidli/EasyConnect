//! Telefon tətbiqindən gələn TLS qoşulmalarını qəbul edən server.
//!
//! Təhlükəsizlik:
//! * yalnız TLS (1.2/1.3), kompüter sertifikatı telefonda pin olunur;
//! * hər QR üçün birdəfəlik, 5 dəqiqəlik təsadüfi token (~190 bit);
//! * token yanlışdırsa, əlaqə dərhal bağlanır, video qəbul edilmir;
//! * bu versiyada PC telefona heç bir idarə əmri göndərmir (yalnız "keyframe istə" və "BYE").

use super::files::{self, FileEvent, FilesIn};
use super::identity::Identity;
use super::proto::{self, msg, video_flags, FrameReader};
use rand::distributions::Alphanumeric;
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::{ErrorKind, Write};
use std::net::{IpAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicU32, AtomicUsize, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};
use tauri::ipc::{Channel, InvokeResponseBody};
use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindowBuilder};

const TOKEN_TTL: Duration = Duration::from_secs(300);
const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
const IDLE_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingQr {
    pub link: String,
    pub qr_svg: String,
    pub hosts: Vec<String>,
    pub port: u16,
    pub expires_in: u64,
    /// Telefonda göstərilən qısa kodla eyni (barmaq izinin ilk 8 simvolu)
    pub code: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct PhoneInfo {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub manufacturer: String,
    #[serde(default)]
    pub model: String,
    #[serde(default)]
    pub os: String,
}

#[derive(Debug, Deserialize)]
struct Hello {
    v: u32,
    token: String,
    #[serde(default)]
    device: PhoneInfo,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionInfo {
    pub id: String,
    pub phone: PhoneInfo,
    pub remote: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct EndedEvent {
    id: String,
    reason: String,
}

struct Session {
    info: SessionInfo,
    outbox: Sender<Vec<u8>>,
    viewer: Arc<Mutex<Viewer>>,
    /// telefona göndərilməyi gözləyən fayl baytları (yaddaş dolmasın deyə)
    pending_file_bytes: Arc<AtomicUsize>,
}

/// Görüntü pəncərəsinə gedən paketin növü (Channel məlumatının ilk baytı)
const KIND_VIDEO: u8 = 1;
const KIND_AUDIO: u8 = 2;

fn tagged(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload.len() + 1);
    out.push(kind);
    out.extend_from_slice(payload);
    out
}

#[derive(Default)]
struct Viewer {
    channel: Option<Channel<InvokeResponseBody>>,
    /// son CONFIG paketi (SPS/PPS) — gec qoşulan pəncərəyə lazımdır
    config: Option<Vec<u8>>,
    /// səs kodekinin konfiqurasiyası (AAC AudioSpecificConfig)
    audio_config: Option<Vec<u8>>,
    /// pəncərə yenicə qoşulub — açar kadr gələnə qədər delta kadrları atırıq
    waiting_key: bool,
    /// telefonun son göndərdiyi icazələr və vəziyyət (gec açılan pəncərə üçün)
    perms: Option<serde_json::Value>,
    state: Option<serde_json::Value>,
}

struct Inner {
    app: AppHandle,
    tls: Arc<rustls::ServerConfig>,
    fingerprint: String,
    port: u16,
    desktop_name: String,
    tokens: Mutex<HashMap<String, Instant>>,
    sessions: Mutex<HashMap<String, Session>>,
    next_file_id: AtomicU32,
}

#[derive(Clone)]
pub struct EasyServer {
    inner: Arc<Inner>,
}

fn random_string(len: usize) -> String {
    rand::thread_rng()
        .sample_iter(&Alphanumeric)
        .take(len)
        .map(char::from)
        .collect()
}

fn desktop_name() -> String {
    // macOS: Finder-də görünən ad ("John's MacBook Air" kimi); mühit dəyişəni yoxdur.
    #[cfg(target_os = "macos")]
    if let Some(name) = std::process::Command::new("scutil")
        .args(["--get", "ComputerName"])
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
    {
        return name;
    }
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "EasyConnect PC".into())
}

/// QR-a yazılacaq lokal IPv4 ünvanları (Wi-Fi/hotspot/Ethernet), şəxsi şəbəkələr əvvəldə.
pub fn local_ipv4s() -> Vec<String> {
    let mut ips: Vec<(u8, String)> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|iface| match iface.ip() {
            IpAddr::V4(v4) if !v4.is_loopback() && !v4.is_link_local() && !v4.is_unspecified() => {
                let name = iface.name.to_lowercase();
                // virtual adapterlər (Hyper-V, WSL, VirtualBox, VPN) sona
                let virtual_if = ["vethernet", "virtual", "vmware", "vbox", "wsl", "docker", "tailscale", "zerotier"]
                    .iter()
                    .any(|k| name.contains(k));
                let rank = if v4.is_private() && !virtual_if { 0 } else if v4.is_private() { 1 } else { 2 };
                Some((rank, v4.to_string()))
            }
            _ => None,
        })
        .collect();
    ips.sort();
    ips.dedup_by(|a, b| a.1 == b.1);
    ips.into_iter().take(4).map(|(_, ip)| ip).collect()
}

fn pct(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

/// Sabit vaxtlı müqayisə (token yoxlamasında vaxt fərqi ilə məlumat sızmasın).
fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

impl EasyServer {
    pub fn start(app: AppHandle, identity: Identity) -> Result<Self, String> {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let tls = rustls::ServerConfig::builder_with_provider(provider)
            .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
            .map_err(|e| e.to_string())?
            .with_no_client_auth()
            .with_single_cert(vec![identity.cert.clone()], identity.key.clone_key())
            .map_err(|e| crate::i18n::tf("easy.tls", &[("e", &e.to_string())]))?;

        // Əvvəlki portu yenidən istifadə etməyə çalışırıq, olmasa təsadüfi port.
        let listener = TcpListener::bind(("0.0.0.0", 47800))
            .or_else(|_| TcpListener::bind(("0.0.0.0", 0)))
            .map_err(|e| crate::i18n::tf("easy.port", &[("e", &e.to_string())]))?;
        let port = listener.local_addr().map_err(|e| e.to_string())?.port();

        let server = Self {
            inner: Arc::new(Inner {
                app,
                tls: Arc::new(tls),
                fingerprint: identity.fingerprint,
                port,
                desktop_name: desktop_name(),
                tokens: Mutex::default(),
                sessions: Mutex::default(),
                next_file_id: AtomicU32::new(files::OUTGOING_ID_BASE),
            }),
        };
        let s = server.clone();
        thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let s = s.clone();
                thread::spawn(move || s.handle(stream));
            }
        });
        Ok(server)
    }

    pub fn new_pairing(&self) -> Result<PairingQr, String> {
        let token = random_string(32);
        {
            let mut tokens = self.inner.tokens.lock().unwrap();
            tokens.retain(|_, exp| *exp > Instant::now());
            tokens.insert(token.clone(), Instant::now() + TOKEN_TTL);
        }
        let hosts = local_ipv4s();
        if hosts.is_empty() {
            return Err(crate::i18n::t("easy.no_network"));
        }
        let link = format!(
            "easyconnect://pair?v=1&n={}&h={}&p={}&t={}&f={}",
            pct(&self.inner.desktop_name),
            hosts.join(","),
            self.inner.port,
            token,
            self.inner.fingerprint
        );
        let qr_svg = crate::pairing::render_qr(&link)?;
        Ok(PairingQr {
            link,
            qr_svg,
            hosts,
            port: self.inner.port,
            expires_in: TOKEN_TTL.as_secs(),
            code: {
                let s = self.inner.fingerprint[..8].to_uppercase();
                format!("{}-{}", &s[..4], &s[4..])
            },
        })
    }

    fn consume_token(&self, token: &str) -> bool {
        let mut tokens = self.inner.tokens.lock().unwrap();
        let now = Instant::now();
        let found = tokens
            .iter()
            .find(|(t, exp)| **exp > now && ct_eq(t.as_bytes(), token.as_bytes()))
            .map(|(t, _)| t.clone());
        match found {
            Some(t) => {
                tokens.remove(&t);
                true
            }
            None => false,
        }
    }

    pub fn sessions(&self) -> Vec<SessionInfo> {
        self.inner
            .sessions
            .lock()
            .unwrap()
            .values()
            .map(|s| s.info.clone())
            .collect()
    }

    /// Görüntü pəncərəsi video axınına qoşulur.
    pub fn attach(&self, id: &str, channel: Channel<InvokeResponseBody>) -> Result<serde_json::Value, String> {
        let sessions = self.inner.sessions.lock().unwrap();
        let s = sessions.get(id).ok_or_else(|| crate::i18n::t("easy.session_ended"))?;
        let mut v = s.viewer.lock().unwrap();
        if let Some(cfg) = &v.config {
            let _ = channel.send(InvokeResponseBody::Raw(tagged(KIND_VIDEO, cfg)));
        }
        if let Some(cfg) = &v.audio_config {
            let _ = channel.send(InvokeResponseBody::Raw(tagged(KIND_AUDIO, cfg)));
        }
        v.channel = Some(channel);
        v.waiting_key = true;
        let _ = s.outbox.send(proto::encode(msg::REQUEST_KEYFRAME, &[]));
        Ok(serde_json::json!({
            "info": s.info,
            "perms": v.perms,
            "state": v.state,
        }))
    }

    /// İdarə əmrini telefona ötürür. Telefon icazəni özü yoxlayır və icazə yoxdursa rədd edir.
    pub fn control(&self, id: &str, payload: &str) -> Result<(), String> {
        if payload.len() > 4096 {
            return Err("əmr çox böyükdür".into());
        }
        serde_json::from_str::<serde_json::Value>(payload).map_err(|e| e.to_string())?;
        let sessions = self.inner.sessions.lock().unwrap();
        let s = sessions.get(id).ok_or_else(|| crate::i18n::t("easy.session_ended"))?;
        s.outbox
            .send(proto::encode(msg::CONTROL, payload.as_bytes()))
            .map_err(|_| crate::i18n::t("easy.session_ended"))
    }

    /// Görüntü pəncərəsi gecikib (kadrlar yığılıb): telefondan dərhal yeni açar kadr istə.
    pub fn request_keyframe(&self, id: &str) {
        if let Some(s) = self.inner.sessions.lock().unwrap().get(id) {
            s.viewer.lock().unwrap().waiting_key = true;
            let _ = s.outbox.send(proto::encode(msg::REQUEST_KEYFRAME, &[]));
        }
    }

    /// Telefonda fayl seçicisini açmağı xahiş edir (telefon "Fayllar" icazəsini yoxlayır).
    pub fn request_file(&self, id: &str) -> Result<(), String> {
        let sessions = self.inner.sessions.lock().unwrap();
        let s = sessions.get(id).ok_or_else(|| crate::i18n::t("easy.session_ended"))?;
        s.outbox
            .send(proto::encode(msg::FILE_REQUEST, &[]))
            .map_err(|_| crate::i18n::t("easy.session_ended"))
    }

    /// Faylları telefona göndərir (ayrı axında, irəliləyiş "easy-file" hadisəsi ilə).
    pub fn send_paths(&self, id: &str, paths: Vec<PathBuf>) -> Result<(), String> {
        let (outbox, pending) = {
            let sessions = self.inner.sessions.lock().unwrap();
            let s = sessions.get(id).ok_or_else(|| crate::i18n::t("easy.session_ended"))?;
            (s.outbox.clone(), s.pending_file_bytes.clone())
        };
        let server = self.clone();
        let sid = id.to_string();
        thread::spawn(move || {
            for path in paths.into_iter().filter(|p| p.is_file()) {
                server.send_one(&sid, &path, &outbox, &pending);
            }
        });
        Ok(())
    }

    fn send_one(&self, sid: &str, path: &std::path::Path, outbox: &Sender<Vec<u8>>, pending: &AtomicUsize) {
        use std::io::Read;
        let name = files::sanitize(&path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default());
        let size = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
        let fid = self.inner.next_file_id.fetch_add(1, Ordering::Relaxed);
        let emit = |bytes: u64, done: bool, ok: bool, reason: Option<String>| {
            let _ = self.inner.app.emit("easy-file", FileEvent {
                id: sid.to_string(), dir: "out", name: name.clone(), done, ok, bytes,
                size: size as i64, path: None, reason,
            });
        };
        let mut file = match std::fs::File::open(path) {
            Ok(f) => f,
            Err(e) => return emit(0, true, false, Some(e.to_string())),
        };
        let begin = serde_json::json!({"id": fid, "name": name, "size": size}).to_string();
        if outbox.send(proto::encode(msg::FILE_BEGIN, begin.as_bytes())).is_err() {
            return;
        }
        let mut buf = vec![0u8; files::CHUNK];
        let mut sent = 0u64;
        let mut last_emit = 0u64;
        loop {
            let n = match file.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => n,
                Err(e) => return emit(sent, true, false, Some(e.to_string())),
            };
            // Axın nəzarəti: növbədə 8 MB-dan çox olmasın
            while pending.load(Ordering::Relaxed) > 8 * 1024 * 1024 {
                if !self.inner.sessions.lock().unwrap().contains_key(sid) {
                    return;
                }
                thread::sleep(Duration::from_millis(10));
            }
            let mut frame = Vec::with_capacity(4 + n);
            frame.extend_from_slice(&fid.to_be_bytes());
            frame.extend_from_slice(&buf[..n]);
            pending.fetch_add(n, Ordering::Relaxed);
            if outbox.send(proto::encode(msg::FILE_DATA, &frame)).is_err() {
                return;
            }
            sent += n as u64;
            if sent - last_emit >= 1024 * 1024 {
                last_emit = sent;
                emit(sent, false, true, None);
            }
        }
        let end = serde_json::json!({"id": fid, "ok": true}).to_string();
        let _ = outbox.send(proto::encode(msg::FILE_END, end.as_bytes()));
        // Yekun nəticəni telefonun cavabı (FILE_END) bildirəcək
    }

    pub fn stop(&self, id: &str) {
        if let Some(s) = self.inner.sessions.lock().unwrap().get(id) {
            let _ = s
                .outbox
                .send(proto::encode(msg::BYE, br#"{"reason":"desktop_closed"}"#));
        }
    }

    pub fn stop_all(&self) {
        for s in self.inner.sessions.lock().unwrap().values() {
            let _ = s
                .outbox
                .send(proto::encode(msg::BYE, br#"{"reason":"desktop_closed"}"#));
        }
    }

    fn handle(&self, tcp: TcpStream) {
        let remote = tcp.peer_addr().map(|a| a.ip().to_string()).unwrap_or_default();
        if let Err(e) = self.handle_inner(tcp, &remote) {
            eprintln!("[easy] {remote}: {e}");
        }
    }

    fn handle_inner(&self, tcp: TcpStream, remote: &str) -> Result<(), String> {
        tcp.set_nodelay(true).ok();
        tcp.set_read_timeout(Some(Duration::from_millis(50))).ok();
        tcp.set_write_timeout(Some(Duration::from_secs(5))).ok();
        let conn = rustls::ServerConnection::new(self.inner.tls.clone()).map_err(|e| e.to_string())?;
        let mut tls = rustls::StreamOwned::new(conn, tcp);
        let mut reader = FrameReader::default();

        // 1) HELLO gözlə
        let deadline = Instant::now() + HELLO_TIMEOUT;
        let hello: Hello = loop {
            if Instant::now() > deadline {
                return Err("HELLO gəlmədi".into());
            }
            if !reader.fill(&mut tls).map_err(|e| e.to_string())? {
                return Err("əlaqə bağlandı".into());
            }
            if let Some((kind, payload)) = reader.next().map_err(|e| e.to_string())? {
                if kind != msg::HELLO {
                    return Err("gözlənilməz ilk mesaj".into());
                }
                break serde_json::from_slice(&payload).map_err(|e| format!("HELLO: {e}"))?;
            }
        };

        if hello.v != 1 || !self.consume_token(&hello.token) {
            let reply = serde_json::json!({"ok": false, "reason": "invalid_token"}).to_string();
            let _ = tls.write_all(&proto::encode(msg::WELCOME, reply.as_bytes()));
            let _ = tls.flush();
            return Err("yanlış və ya köhnəlmiş token".into());
        }
        let reply = serde_json::json!({"ok": true, "desktop": self.inner.desktop_name, "control": true}).to_string();
        tls.write_all(&proto::encode(msg::WELCOME, reply.as_bytes()))
            .and_then(|_| tls.flush())
            .map_err(|e| e.to_string())?;

        // 2) Sessiyanı qeyd et və görüntü pəncərəsini aç
        let id = random_string(10).to_lowercase();
        let (tx, rx): (Sender<Vec<u8>>, Receiver<Vec<u8>>) = mpsc::channel();
        let viewer = Arc::new(Mutex::new(Viewer::default()));
        let pending_file_bytes = Arc::new(AtomicUsize::new(0));
        let mut phone = hello.device;
        phone.name = phone.name.chars().take(64).collect();
        let info = SessionInfo {
            id: id.clone(),
            phone,
            remote: remote.to_string(),
        };
        self.inner.sessions.lock().unwrap().insert(
            id.clone(),
            Session {
                info: info.clone(),
                outbox: tx,
                viewer: viewer.clone(),
                pending_file_bytes: pending_file_bytes.clone(),
            },
        );
        let _ = self.inner.app.emit("easy-session", &info);
        self.open_viewer(&info);

        // 3) Əsas dövr
        let downloads = self
            .inner
            .app
            .path()
            .download_dir()
            .unwrap_or_else(|_| std::env::temp_dir())
            .join("EasyConnect");
        let mut files_in = FilesIn::new(downloads);
        let reason = self.pump(&id, &mut tls, &mut reader, &rx, &viewer, &mut files_in, &pending_file_bytes);
        files_in.abort_all();
        self.inner.sessions.lock().unwrap().remove(&id);
        let ended = EndedEvent { id: id.clone(), reason };
        let _ = self.inner.app.emit("easy-ended", &ended);
        let _ = tls.flush();
        tls.conn.send_close_notify();
        let _ = tls.flush();
        Ok(())
    }

    fn pump(
        &self,
        id: &str,
        tls: &mut rustls::StreamOwned<rustls::ServerConnection, TcpStream>,
        reader: &mut FrameReader,
        outbox: &Receiver<Vec<u8>>,
        viewer: &Arc<Mutex<Viewer>>,
        files_in: &mut FilesIn,
        pending: &AtomicUsize,
    ) -> String {
        let mut last_seen = Instant::now();
        loop {
            // PC → telefon
            while let Ok(bytes) = outbox.try_recv() {
                let is_bye = bytes.first() == Some(&msg::BYE);
                if tls.write_all(&bytes).and_then(|_| tls.flush()).is_err() {
                    return "connection_lost".into();
                }
                if bytes.first() == Some(&msg::FILE_DATA) && bytes.len() > 9 {
                    pending.fetch_sub(bytes.len() - 9, Ordering::Relaxed);
                }
                if is_bye {
                    return "desktop_closed".into();
                }
            }
            // telefon → PC
            match reader.fill(tls) {
                Ok(true) => {}
                Ok(false) => return "phone_closed".into(),
                Err(e) if e.kind() == ErrorKind::ConnectionReset => return "connection_lost".into(),
                Err(_) => return "connection_lost".into(),
            }
            loop {
                let frame = match reader.next() {
                    Ok(Some(f)) => f,
                    Ok(None) => break,
                    Err(_) => return "protocol_error".into(),
                };
                last_seen = Instant::now();
                match frame {
                    (msg::VIDEO, payload) => self.on_video(viewer, payload),
                    (msg::AUDIO, payload) => self.on_audio(viewer, payload),
                    (msg::FILE_BEGIN, payload) => self.on_file_begin(id, files_in, &payload),
                    (msg::FILE_DATA, payload) => self.on_file_data(id, files_in, &payload),
                    (msg::FILE_END, payload) => self.on_file_end(id, files_in, &payload),
                    (msg::STATE, payload) => {
                        if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&payload) {
                            viewer.lock().unwrap().state = Some(v.clone());
                            let _ = self.inner.app.emit("easy-state", serde_json::json!({"id": id, "state": v}));
                        }
                    }
                    (msg::PERMS, payload) => {
                        if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&payload) {
                            viewer.lock().unwrap().perms = Some(v.clone());
                            let _ = self.inner.app.emit("easy-perms", serde_json::json!({"id": id, "perms": v}));
                        }
                    }
                    (msg::PING, _) => {
                        if tls
                            .write_all(&proto::encode(msg::PONG, &[]))
                            .and_then(|_| tls.flush())
                            .is_err()
                        {
                            return "connection_lost".into();
                        }
                    }
                    (msg::BYE, payload) => {
                        let reason = serde_json::from_slice::<serde_json::Value>(&payload)
                            .ok()
                            .and_then(|v| v.get("reason").and_then(|r| r.as_str()).map(str::to_string))
                            .unwrap_or_else(|| "phone_closed".into());
                        return reason;
                    }
                    // Ölçü məlumatı telefon görüntüləyicisi üçündür; WebCodecs ölçünü özü bilir
                    (msg::VIDEO_META, _) => {}
                    _ => {} // naməlum mesajlar nəzərə alınmır
                }
            }
            if last_seen.elapsed() > IDLE_TIMEOUT {
                return "timeout".into();
            }
        }
    }

    fn on_video(&self, viewer: &Arc<Mutex<Viewer>>, payload: Vec<u8>) {
        let Some(&flags) = payload.first() else { return };
        let mut v = viewer.lock().unwrap();
        if flags & video_flags::CONFIG != 0 {
            v.config = Some(payload.clone());
        } else if v.waiting_key {
            if flags & video_flags::KEY == 0 {
                return;
            }
            v.waiting_key = false;
        }
        if let Some(ch) = &v.channel {
            if ch.send(InvokeResponseBody::Raw(tagged(KIND_VIDEO, &payload))).is_err() {
                v.channel = None;
            }
        }
    }

    fn on_audio(&self, viewer: &Arc<Mutex<Viewer>>, payload: Vec<u8>) {
        let Some(&flags) = payload.first() else { return };
        let mut v = viewer.lock().unwrap();
        if flags & video_flags::CONFIG != 0 {
            v.audio_config = Some(payload.clone());
        }
        if let Some(ch) = &v.channel {
            if ch.send(InvokeResponseBody::Raw(tagged(KIND_AUDIO, &payload))).is_err() {
                v.channel = None;
            }
        }
    }

    fn emit_file(&self, ev: FileEvent) {
        let _ = self.inner.app.emit("easy-file", ev);
    }

    fn on_file_begin(&self, sid: &str, files_in: &mut FilesIn, payload: &[u8]) {
        let Ok(v) = serde_json::from_slice::<serde_json::Value>(payload) else { return };
        let fid = v.get("id").and_then(|x| x.as_u64()).unwrap_or(u64::MAX);
        if fid >= files::OUTGOING_ID_BASE as u64 {
            return; // telefon bizim id aralığımızı istifadə edə bilməz
        }
        let name = v.get("name").and_then(|x| x.as_str()).unwrap_or("file");
        let size = v.get("size").and_then(|x| x.as_i64()).unwrap_or(-1);
        match files_in.begin(fid as u32, name, size) {
            Ok(shown) => self.emit_file(FileEvent {
                id: sid.into(), dir: "in", name: shown, done: false, ok: true, bytes: 0, size, path: None, reason: None,
            }),
            Err(e) => self.emit_file(FileEvent {
                id: sid.into(), dir: "in", name: files::sanitize(name), done: true, ok: false, bytes: 0, size, path: None, reason: Some(e),
            }),
        }
    }

    fn on_file_data(&self, sid: &str, files_in: &mut FilesIn, payload: &[u8]) {
        if payload.len() < 4 {
            return;
        }
        let fid = u32::from_be_bytes([payload[0], payload[1], payload[2], payload[3]]);
        match files_in.data(fid, &payload[4..]) {
            Ok((name, written, size)) => {
                // ~1 MB-dan bir irəliləyiş
                if written / (1024 * 1024) != (written - (payload.len() as u64 - 4)) / (1024 * 1024) {
                    self.emit_file(FileEvent {
                        id: sid.into(), dir: "in", name, done: false, ok: true, bytes: written, size, path: None, reason: None,
                    });
                }
            }
            Err(e) if e != "unknown" => self.emit_file(FileEvent {
                id: sid.into(), dir: "in", name: String::new(), done: true, ok: false, bytes: 0, size: -1, path: None, reason: Some(e),
            }),
            Err(_) => {}
        }
    }

    fn on_file_end(&self, sid: &str, files_in: &mut FilesIn, payload: &[u8]) {
        let Ok(v) = serde_json::from_slice::<serde_json::Value>(payload) else { return };
        let fid = v.get("id").and_then(|x| x.as_u64()).unwrap_or(u64::MAX);
        let ok = v.get("ok").and_then(|x| x.as_bool()).unwrap_or(false);
        let reason = v.get("reason").and_then(|x| x.as_str()).map(str::to_string);
        if fid >= files::OUTGOING_ID_BASE as u64 {
            // Telefonun bizim göndərdiyimiz fayla cavabı
            self.emit_file(FileEvent {
                id: sid.into(), dir: "out", name: String::new(), done: true, ok, bytes: 0, size: -1, path: None, reason,
            });
            return;
        }
        if let Some((name, res)) = files_in.end(fid as u32, ok) {
            match res {
                Ok(path) => self.emit_file(FileEvent {
                    id: sid.into(), dir: "in", name, done: true, ok: true, bytes: 0, size: -1,
                    path: Some(path.display().to_string()), reason: None,
                }),
                Err(e) => self.emit_file(FileEvent {
                    id: sid.into(), dir: "in", name, done: true, ok: false, bytes: 0, size: -1, path: None, reason: Some(e),
                }),
            }
        }
    }

    fn open_viewer(&self, info: &SessionInfo) {
        let title = if info.phone.name.is_empty() {
            crate::i18n::t("easy.viewer_title")
        } else {
            format!("{} — EasyConnect", info.phone.name)
        };
        let url = format!("viewer.html?id={}", info.id);
        let app = self.inner.app.clone();
        let label = format!("viewer-{}", info.id);
        // Pəncərəni əsas axında yaratmaq daha etibarlıdır.
        let _ = self.inner.app.run_on_main_thread(move || {
            if app.get_webview_window(&label).is_some() {
                return;
            }
            let _ = WebviewWindowBuilder::new(&app, &label, WebviewUrl::App(url.into()))
                .title(title)
                .inner_size(420.0, 860.0)
                .min_inner_size(240.0, 320.0)
                .build();
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constant_time_eq() {
        assert!(ct_eq(b"abc", b"abc"));
        assert!(!ct_eq(b"abc", b"abd"));
        assert!(!ct_eq(b"abc", b"abcd"));
    }

    #[test]
    fn percent_encoding() {
        assert_eq!(pct("DESKTOP-A1 b&c"), "DESKTOP-A1%20b%26c");
    }
}
