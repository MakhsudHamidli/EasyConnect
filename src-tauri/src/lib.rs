mod adb;
mod easy;
mod errors;
mod i18n;
mod mdns;
mod pairing;
mod proc;
mod scrcpy;
mod store;
mod tools;

use adb::{Adb, Device};
use mdns::MdnsCache;
use pairing::{PairingDeps, PairingSessions, QrPairing};
use scrcpy::{LaunchOptions, Sessions};
use serde::Serialize;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};
use store::{SavedDevice, Store};
use tauri::{AppHandle, Emitter, Manager, RunEvent, State};
use tauri_plugin_opener::OpenerExt;
use tools::Tools;

#[derive(Clone)]
struct Core {
    tools: Tools,
    adb: Adb,
}

#[derive(Clone, Default)]
struct DeviceInfo {
    manufacturer: Option<String>,
    model: Option<String>,
    android: Option<String>,
}

struct AppState {
    core: Result<Core, String>,
    mdns: MdnsCache,
    store: Arc<Store>,
    sessions: Sessions,
    pairing: PairingSessions,
    info: Arc<Mutex<HashMap<String, DeviceInfo>>>,
}

impl AppState {
    fn core(&self) -> Result<&Core, String> {
        self.core.as_ref().map_err(Clone::clone)
    }

    fn deps(&self, app: &AppHandle) -> Result<PairingDeps, String> {
        Ok(PairingDeps {
            app: app.clone(),
            adb: self.core()?.adb.clone(),
            mdns: self.mdns.clone(),
            store: self.store.clone(),
        })
    }
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
struct DeviceView {
    #[serde(flatten)]
    device: Device,
    display_name: String,
    manufacturer: Option<String>,
    android_version: Option<String>,
    mirroring: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ToolsStatus {
    ok: bool,
    error: Option<String>,
    dir: Option<String>,
    adb_version: Option<String>,
    usb_driver_url: &'static str,
}

fn display_name(d: &Device, info: Option<&DeviceInfo>) -> String {
    let model = info
        .and_then(|i| i.model.clone())
        .or_else(|| d.model.clone());
    let manufacturer = info.and_then(|i| i.manufacturer.clone());
    match (manufacturer, model) {
        (Some(m), Some(md)) if !md.to_lowercase().starts_with(&m.to_lowercase()) => {
            format!("{} {md}", capitalize(&m))
        }
        (_, Some(md)) => md,
        _ => d.serial.clone(),
    }
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

fn collect_devices(state: &AppState) -> Result<Vec<DeviceView>, String> {
    let core = state.core()?;
    let devices = core.adb.devices()?;
    let running = state.sessions.running();
    let mut views = Vec::with_capacity(devices.len());
    for d in devices {
        if d.state == "device" && !state.info.lock().unwrap().contains_key(&d.serial) {
            let info = DeviceInfo {
                manufacturer: core.adb.getprop(&d.serial, "ro.product.manufacturer"),
                model: core.adb.getprop(&d.serial, "ro.product.model"),
                android: core.adb.getprop(&d.serial, "ro.build.version.release"),
            };
            state.info.lock().unwrap().insert(d.serial.clone(), info);
        }
        let info = state.info.lock().unwrap().get(&d.serial).cloned();
        views.push(DeviceView {
            display_name: display_name(&d, info.as_ref()),
            manufacturer: info.as_ref().and_then(|i| i.manufacturer.clone()),
            android_version: info.as_ref().and_then(|i| i.android.clone()),
            mirroring: running.contains(&d.serial),
            device: d,
        });
    }
    Ok(views)
}

// ---------------------------------------------------------------- commands

#[tauri::command(async)]
fn tools_status(state: State<'_, AppState>) -> ToolsStatus {
    match state.core() {
        Ok(core) => ToolsStatus {
            ok: true,
            error: None,
            dir: Some(core.tools.dir.display().to_string()),
            adb_version: core.adb.version().ok(),
            usb_driver_url: errors::USB_DRIVER_URL,
        },
        Err(e) => ToolsStatus {
            ok: false,
            error: Some(e),
            dir: None,
            adb_version: None,
            usb_driver_url: errors::USB_DRIVER_URL,
        },
    }
}

#[tauri::command(async)]
fn list_devices(state: State<'_, AppState>) -> Result<Vec<DeviceView>, String> {
    collect_devices(&state)
}

#[tauri::command(async)]
fn start_qr_pairing(app: AppHandle, state: State<'_, AppState>) -> Result<QrPairing, String> {
    pairing::start_qr(state.deps(&app)?, &state.pairing)
}

#[tauri::command]
fn cancel_pairing(state: State<'_, AppState>, session_id: String) {
    state.pairing.cancel(&session_id);
}

#[tauri::command(async)]
fn pair_with_code(app: AppHandle, state: State<'_, AppState>, host: String, code: String) -> Result<String, String> {
    pairing::pair_with_code(&state.deps(&app)?, &host, &code)
}

#[tauri::command(async)]
fn connect_address(app: AppHandle, state: State<'_, AppState>, address: String) -> Result<String, String> {
    pairing::connect_address(&state.deps(&app)?, &address)
}

#[tauri::command(async)]
fn disconnect_device(state: State<'_, AppState>, serial: String) -> Result<(), String> {
    let _ = state.sessions.stop(&serial);
    // İstifadəçi özü ayırıbsa, avtomatik yenidən qoşulma onu geri qoşmasın.
    let ip = serial.rsplit_once(':').map(|(ip, _)| ip.to_string());
    for saved in state.store.list() {
        let same_ip = ip.as_deref() == Some(saved.ip.as_str());
        let same_mdns = saved
            .mdns_instance
            .as_deref()
            .map(|m| serial.starts_with(m))
            .unwrap_or(false);
        if same_ip || same_mdns {
            state.store.set_auto_reconnect(&saved.id, false);
        }
    }
    state.core()?.adb.disconnect(&serial).map(|_| ())
}

#[tauri::command(async)]
fn switch_to_wifi(state: State<'_, AppState>, serial: String) -> Result<String, String> {
    let core = state.core()?;
    let name = core.adb.getprop(&serial, "ro.product.model");
    let addr = core.adb.switch_to_wifi(&serial)?;
    let (ip, port) = addr.rsplit_once(':').unwrap_or((&addr, "5555"));
    state.store.upsert(SavedDevice {
        id: ip.to_string(),
        name: name.unwrap_or_else(|| ip.to_string()),
        ip: ip.to_string(),
        port: port.parse().unwrap_or(5555),
        mdns_instance: None,
        auto_reconnect: true,
        last_seen: 0,
    });
    Ok(addr)
}

#[tauri::command(async)]
fn start_mirroring(
    app: AppHandle,
    state: State<'_, AppState>,
    serial: String,
    options: LaunchOptions,
) -> Result<(), String> {
    let core = state.core()?;
    let devices = core.adb.devices()?;
    let dev = devices
        .iter()
        .find(|d| d.serial == serial)
        .ok_or_else(|| i18n::t("dev.gone"))?;
    if dev.state != "device" {
        return Err(errors::explain(&dev.state));
    }
    let info = state.info.lock().unwrap().get(&serial).cloned();
    let title = format!("{} — EasyConnect", display_name(dev, info.as_ref()));
    let record_path = if options.record {
        let base: PathBuf = app
            .path()
            .video_dir()
            .or_else(|_| app.path().home_dir())
            .map_err(|e| e.to_string())?;
        let safe: String = display_name(dev, info.as_ref())
            .chars()
            .map(|c| if c.is_alphanumeric() { c } else { '_' })
            .collect();
        Some(base.join("EasyConnect").join(format!("{safe}_{}.mp4", store::now())))
    } else {
        None
    };
    state
        .sessions
        .start(&app, &core.tools, &serial, &title, &options, record_path)
}

#[tauri::command]
fn stop_mirroring(state: State<'_, AppState>, serial: String) -> Result<(), String> {
    state.sessions.stop(&serial)
}

#[tauri::command]
fn saved_devices(state: State<'_, AppState>) -> Vec<SavedDevice> {
    state.store.list()
}

#[tauri::command]
fn forget_device(state: State<'_, AppState>, id: String) {
    state.store.forget(&id);
}

#[tauri::command]
fn set_auto_reconnect(state: State<'_, AppState>, id: String, value: bool) {
    state.store.set_auto_reconnect(&id, value);
}

#[tauri::command]
fn open_url(app: AppHandle, url: String) -> Result<(), String> {
    if !url.starts_with("https://") {
        return Err(i18n::t("url.https_only"));
    }
    app.opener()
        .open_url(url, None::<&str>)
        .map_err(|e| e.to_string())
}

/// İnterfeys dili (az/ru/en) — Rust-dan gələn mesajlar da bu dildə olur.
#[tauri::command]
fn set_language(lang: String) {
    i18n::set(&lang);
}

/// İki rejimin birləşməsi: "Tətbiqlə" qoşulmuş telefon eyni zamanda adb ilə (Developer mode)
/// bu kompüterə qoşuludursa, onun adb serial-ını qaytarır — o zaman scrcpy ilə açmaq olar
/// (Accessibility lazım deyil). Uyğunluq: Wi-Fi IP ünvanı və ya istehsalçı+model.
#[tauri::command(async)]
fn easy_find_adb(state: State<'_, AppState>, easy: State<'_, easy::EasyState>, id: String) -> Option<String> {
    let info = easy.session(&id)?;
    let core = state.core().ok()?;
    let norm = |s: &str| s.trim().to_lowercase().replace('_', " ");
    let model = norm(&info.phone.model);
    let manufacturer = norm(&info.phone.manufacturer);
    core.adb
        .devices()
        .ok()?
        .into_iter()
        .filter(|d| d.state == "device")
        .find(|d| {
            if d.ip.as_deref() == Some(info.remote.as_str()) {
                return true;
            }
            if model.is_empty() || d.model.as_deref().map(norm).as_deref() != Some(model.as_str()) {
                return false;
            }
            // Eyni modeldən iki telefon ola bilər — istehsalçını da yoxlayırıq
            let cached = state.info.lock().unwrap().get(&d.serial).and_then(|i| i.manufacturer.clone());
            cached.map(|m| norm(&m) == manufacturer).unwrap_or(true)
        })
        .map(|d| d.serial)
}

// ---------------------------------------------------------- background work

/// Cihaz siyahısını izləyir və dəyişəndə `devices` hadisəsini göndərir.
fn spawn_device_watcher(app: AppHandle) {
    thread::spawn(move || {
        let mut last: Option<Vec<DeviceView>> = None;
        loop {
            let state = app.state::<AppState>();
            if let Ok(list) = collect_devices(&state) {
                if last.as_ref() != Some(&list) {
                    let _ = app.emit("devices", &list);
                    last = Some(list);
                }
            }
            thread::sleep(Duration::from_millis(1500));
        }
    });
}

/// Yadda saxlanmış Wi-Fi cihazlarına avtomatik yenidən qoşulur.
fn spawn_reconnector(app: AppHandle) {
    thread::spawn(move || {
        let mut last_try: HashMap<String, Instant> = HashMap::new();
        loop {
            thread::sleep(Duration::from_secs(5));
            let state = app.state::<AppState>();
            let Ok(core) = state.core() else { return };
            let Ok(devices) = core.adb.devices() else { continue };
            for saved in state.store.list().into_iter().filter(|s| s.auto_reconnect) {
                let online = devices.iter().any(|d| {
                    d.ip.as_deref() == Some(saved.ip.as_str())
                        || saved
                            .mdns_instance
                            .as_deref()
                            .map(|m| d.serial.starts_with(m))
                            .unwrap_or(false)
                });
                if online {
                    continue;
                }
                if last_try
                    .get(&saved.id)
                    .map(|t| t.elapsed() < Duration::from_secs(20))
                    .unwrap_or(false)
                {
                    continue;
                }
                last_try.insert(saved.id.clone(), Instant::now());
                // Wireless debugging portu hər dəfə dəyişir — əvvəlcə mDNS-də axtarırıq.
                let svc = saved
                    .mdns_instance
                    .as_deref()
                    .and_then(|m| state.mdns.find_connect_by_instance(&core.adb, m))
                    .or_else(|| state.mdns.find_connect_by_ip(&core.adb, &saved.ip));
                let addr = match svc {
                    Some(s) => s.addr(),
                    None if saved.port == 5555 => format!("{}:{}", saved.ip, saved.port),
                    None => continue,
                };
                if core.adb.connect(&addr, Duration::from_secs(4)).is_ok() {
                    let mut updated = saved.clone();
                    if let Some((_, p)) = addr.rsplit_once(':') {
                        updated.port = p.parse().unwrap_or(saved.port);
                    }
                    state.store.upsert(updated);
                }
            }
        }
    });
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .on_window_event(|window, event| {
            // Görüntü pəncərəsi bağlandı → telefona paylaşımı dayandırmaq üçün BYE göndər.
            if let tauri::WindowEvent::Destroyed = event {
                if let Some(id) = window.label().strip_prefix("viewer-") {
                    window.app_handle().state::<easy::EasyState>().stop(id);
                }
            }
        })
        .setup(|app| {
            let handle = app.handle().clone();
            let core = tools::locate(&handle).map(|t| Core {
                adb: Adb::new(t.adb.clone()),
                tools: t,
            });
            if let Ok(core) = &core {
                if let Err(e) = core.adb.start_server() {
                    eprintln!("[adb] start-server: {e}");
                }
            }
            let config_dir = app
                .path()
                .app_config_dir()
                .unwrap_or_else(|_| PathBuf::from("."));
            let mdns = MdnsCache::default();
            mdns::start_browser(mdns.clone());
            let ok = core.is_ok();
            app.manage(easy::EasyState::new(config_dir.clone()));
            app.manage(AppState {
                core,
                mdns,
                store: Arc::new(Store::load(config_dir.join("devices.json"))),
                sessions: Sessions::default(),
                pairing: PairingSessions::default(),
                info: Arc::default(),
            });
            if ok {
                spawn_device_watcher(handle.clone());
                spawn_reconnector(handle);
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            tools_status,
            list_devices,
            start_qr_pairing,
            cancel_pairing,
            pair_with_code,
            connect_address,
            disconnect_device,
            switch_to_wifi,
            start_mirroring,
            stop_mirroring,
            saved_devices,
            forget_device,
            set_auto_reconnect,
            open_url,
            easy::easy_new_pairing,
            easy::easy_attach,
            easy::easy_stop,
            easy::easy_sessions,
            easy::easy_local_ips,
            easy::easy_control,
            easy::easy_request_key,
            easy::easy_send_files,
            easy::easy_send_paths,
            easy::easy_request_file,
            easy::easy_open_downloads,
            easy::easy_focus,
            set_language,
            easy_find_adb,
        ])
        .build(tauri::generate_context!())
        .expect("EasyConnect başladıla bilmədi");

    app.run(|handle, event| {
        if let RunEvent::Exit = event {
            let state = handle.state::<AppState>();
            state.sessions.stop_all();
            state.pairing.cancel_all();
            handle.state::<easy::EasyState>().stop_all();
            // Bizim adb serverimiz açıq qalsa, proqramı yeniləmək/silmək mümkün olmur.
            if let Ok(core) = state.core() {
                let _ = core.adb.exec(&["kill-server"], Duration::from_secs(5));
            }
        }
    });
}
