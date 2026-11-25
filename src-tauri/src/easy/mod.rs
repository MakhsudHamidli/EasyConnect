//! "Asan rejim": developer mode tələb etməyən, telefon tətbiqi ilə qoşulma.

pub mod files;
pub mod identity;
pub mod proto;
pub mod server;

use server::{EasyServer, PairingQr, SessionInfo};
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::ipc::{Channel, InvokeResponseBody};
use tauri::{AppHandle, State};

/// Server ilk lazım olanda başladılır (firewall sualı yalnız istifadəçi bu rejimi açanda çıxsın).
pub struct EasyState {
    pub config_dir: PathBuf,
    pub server: Mutex<Option<EasyServer>>,
}

impl EasyState {
    pub fn new(config_dir: PathBuf) -> Self {
        Self {
            config_dir,
            server: Mutex::new(None),
        }
    }

    fn get_or_start(&self, app: &AppHandle) -> Result<EasyServer, String> {
        let mut guard = self.server.lock().unwrap();
        if let Some(s) = guard.as_ref() {
            return Ok(s.clone());
        }
        let identity = identity::load_or_create(&self.config_dir)?;
        let server = EasyServer::start(app.clone(), identity)?;
        *guard = Some(server.clone());
        Ok(server)
    }

    pub fn stop_all(&self) {
        if let Some(s) = self.server.lock().unwrap().as_ref() {
            s.stop_all();
        }
    }

    pub fn stop(&self, id: &str) {
        if let Some(s) = self.server.lock().unwrap().as_ref() {
            s.stop(id);
        }
    }
}

#[tauri::command(async)]
pub fn easy_new_pairing(app: AppHandle, state: State<'_, EasyState>) -> Result<PairingQr, String> {
    state.get_or_start(&app)?.new_pairing()
}

#[tauri::command(async)]
pub fn easy_attach(
    app: AppHandle,
    state: State<'_, EasyState>,
    id: String,
    channel: Channel<InvokeResponseBody>,
) -> Result<serde_json::Value, String> {
    state.get_or_start(&app)?.attach(&id, channel)
}

/// Kompüterin hazırkı lokal IP-ləri — şəbəkə dəyişəndə QR-ı yeniləmək üçün.
#[tauri::command(async)]
pub fn easy_local_ips() -> Vec<String> {
    server::local_ipv4s()
}

#[tauri::command]
pub fn easy_stop(state: State<'_, EasyState>, id: String) {
    state.stop(&id);
}

#[tauri::command]
pub fn easy_sessions(state: State<'_, EasyState>) -> Vec<SessionInfo> {
    state
        .server
        .lock()
        .unwrap()
        .as_ref()
        .map(|s| s.sessions())
        .unwrap_or_default()
}

/// Görüntü pəncərəsindən gələn idarə əmri. İcazə telefonda yoxlanılır.
#[tauri::command]
pub fn easy_control(state: State<'_, EasyState>, id: String, payload: String) -> Result<(), String> {
    match state.server.lock().unwrap().as_ref() {
        Some(s) => s.control(&id, &payload),
        None => Err(crate::i18n::t("easy.no_server")),
    }
}

impl EasyState {
    /// Aktiv sessiyanın məlumatı (telefon modeli, IP).
    pub fn session(&self, id: &str) -> Option<SessionInfo> {
        self.server
            .lock()
            .unwrap()
            .as_ref()?
            .sessions()
            .into_iter()
            .find(|s| s.id == id)
    }
}

/// Görüntü pəncərəsi gecikməni aşkarlayıb: yığılmış kadrlar atılır, yeni açar kadr istənir.
#[tauri::command]
pub fn easy_request_key(state: State<'_, EasyState>, id: String) {
    if let Some(s) = state.server.lock().unwrap().as_ref() {
        s.request_keyframe(&id);
    }
}

/// Telefona fayl göndərmək: kompüterdə fayl seçmə dialoqu açılır.
#[tauri::command(async)]
pub fn easy_send_files(app: AppHandle, state: State<'_, EasyState>, id: String) -> Result<(), String> {
    use tauri_plugin_dialog::DialogExt;
    let Some(picked) = app.dialog().file().blocking_pick_files() else { return Ok(()) };
    let paths: Vec<PathBuf> = picked.into_iter().filter_map(|p| p.into_path().ok()).collect();
    match state.server.lock().unwrap().as_ref() {
        Some(s) => s.send_paths(&id, paths),
        None => Err(crate::i18n::t("easy.no_server")),
    }
}

/// Pəncərəyə sürüklənib atılan fayllar.
#[tauri::command]
pub fn easy_send_paths(state: State<'_, EasyState>, id: String, paths: Vec<String>) -> Result<(), String> {
    let paths: Vec<PathBuf> = paths.into_iter().map(PathBuf::from).collect();
    match state.server.lock().unwrap().as_ref() {
        Some(s) => s.send_paths(&id, paths),
        None => Err(crate::i18n::t("easy.no_server")),
    }
}

/// Telefondan fayl istəmək (telefonda fayl seçicisi açılır).
#[tauri::command]
pub fn easy_request_file(state: State<'_, EasyState>, id: String) -> Result<(), String> {
    match state.server.lock().unwrap().as_ref() {
        Some(s) => s.request_file(&id),
        None => Err(crate::i18n::t("easy.no_server")),
    }
}

/// Qəbul edilən faylların qovluğunu açır (Downloads/EasyConnect).
#[tauri::command]
pub fn easy_open_downloads(app: AppHandle) -> Result<(), String> {
    use tauri::Manager;
    use tauri_plugin_opener::OpenerExt;
    let dir = app.path().download_dir().map_err(|e| e.to_string())?.join("EasyConnect");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    app.opener().open_path(dir.display().to_string(), None::<&str>).map_err(|e| e.to_string())
}

/// Sessiyanın görüntü pəncərəsini önə gətirir (cihaz siyahısından).
#[tauri::command]
pub fn easy_focus(app: AppHandle, id: String) {
    use tauri::Manager;
    if let Some(w) = app.get_webview_window(&format!("viewer-{id}")) {
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}
