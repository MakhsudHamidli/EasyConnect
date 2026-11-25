//! QR kod ilə Wireless debugging qoşulması (Android 11+).
//!
//! Axın (Android Studio ilə eyni protokol):
//! 1. Təsadüfi ad və parol yaradılır, QR-a `WIFI:T:ADB;S:<ad>;P:<parol>;;` yazılır.
//! 2. Telefon QR-ı skan edəndə şəbəkədə `<ad>._adb-tls-pairing._tcp` xidmətini elan edir.
//! 3. Biz onu mDNS ilə tapıb `adb pair ip:port parol` icra edirik.
//! 4. Pairing-dən sonra telefonun `_adb-tls-connect._tcp` xidmətinə qoşuluruq.

use crate::adb::{Adb, MdnsService};
use crate::mdns::MdnsCache;
use crate::store::{SavedDevice, Store};
use qrcode::render::svg;
use qrcode::QrCode;
use rand::distributions::Alphanumeric;
use rand::Rng;
use serde::Serialize;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter};

const PAIRING_TIMEOUT: Duration = Duration::from_secs(180);

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QrPairing {
    pub session_id: String,
    pub name: String,
    pub password: String,
    pub qr_svg: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingEvent {
    pub session_id: String,
    /// waiting | found | paired | connected | error | timeout
    pub stage: String,
    pub message: String,
    pub serial: Option<String>,
}

#[derive(Clone, Default)]
pub struct PairingSessions {
    cancels: Arc<Mutex<HashMap<String, Arc<AtomicBool>>>>,
}

impl PairingSessions {
    pub fn cancel(&self, id: &str) {
        if let Some(flag) = self.cancels.lock().unwrap().remove(id) {
            flag.store(true, Ordering::SeqCst);
        }
    }

    pub fn cancel_all(&self) {
        for (_, flag) in self.cancels.lock().unwrap().drain() {
            flag.store(true, Ordering::SeqCst);
        }
    }
}

fn random_string(len: usize) -> String {
    rand::thread_rng()
        .sample_iter(&Alphanumeric)
        .take(len)
        .map(char::from)
        .collect()
}

pub fn qr_payload(name: &str, password: &str) -> String {
    format!("WIFI:T:ADB;S:{name};P:{password};;")
}

pub fn render_qr(payload: &str) -> Result<String, String> {
    let code = QrCode::new(payload.as_bytes()).map_err(|e| crate::i18n::tf("pair.qr_failed", &[("e", &e.to_string())]))?;
    Ok(code
        .render::<svg::Color<'_>>()
        .min_dimensions(240, 240)
        .quiet_zone(true)
        .dark_color(svg::Color("#111111"))
        .light_color(svg::Color("#ffffff"))
        .build())
}

pub struct PairingDeps {
    pub app: AppHandle,
    pub adb: Adb,
    pub mdns: MdnsCache,
    pub store: Arc<Store>,
}

pub fn start_qr(deps: PairingDeps, sessions: &PairingSessions) -> Result<QrPairing, String> {
    // Köhnə QR sessiyalarını dayandırırıq — eyni anda bir QR kifayətdir.
    sessions.cancel_all();

    let name = format!("easyconnect-{}", random_string(8).to_lowercase());
    let password = random_string(12);
    let qr_svg = render_qr(&qr_payload(&name, &password))?;
    let session_id = random_string(16);

    let cancel = Arc::new(AtomicBool::new(false));
    sessions
        .cancels
        .lock()
        .unwrap()
        .insert(session_id.clone(), cancel.clone());

    let result = QrPairing {
        session_id: session_id.clone(),
        name: name.clone(),
        password: password.clone(),
        qr_svg,
    };

    let sessions = sessions.clone();
    thread::spawn(move || {
        let emit = |stage: &str, message: &str, serial: Option<String>| {
            let _ = deps.app.emit(
                "pairing",
                PairingEvent {
                    session_id: session_id.clone(),
                    stage: stage.into(),
                    message: message.into(),
                    serial,
                },
            );
        };
        emit("waiting", &crate::i18n::t("pair.waiting"), None);

        let start = Instant::now();
        let found = loop {
            if cancel.load(Ordering::SeqCst) {
                return;
            }
            if start.elapsed() > PAIRING_TIMEOUT {
                emit("timeout", &crate::i18n::t("pair.timeout"), None);
                sessions.cancel(&session_id);
                return;
            }
            if let Some(svc) = deps.mdns.find_pairing(&deps.adb, &name) {
                break svc;
            }
            thread::sleep(Duration::from_millis(700));
        };

        emit("found", &crate::i18n::tf("pair.found", &[("ip", &found.ip)]), None);
        let outcome = pair_and_connect(&deps, &found, &password, &cancel);
        sessions.cancel(&session_id);
        match outcome {
            Ok(serial) => emit("connected", &crate::i18n::t("pair.connected"), Some(serial)),
            Err(e) => emit("error", &e, None),
        }
    });

    Ok(result)
}

fn pair_and_connect(
    deps: &PairingDeps,
    svc: &MdnsService,
    password: &str,
    cancel: &AtomicBool,
) -> Result<String, String> {
    let paired = deps.adb.pair(&svc.addr(), password)?;
    // "Successfully paired to 192.168.1.5:37555 [guid=adb-R58M12345-AbCdEf]"
    let guid = paired
        .split("guid=")
        .nth(1)
        .map(|s| s.trim_end_matches(|c: char| c == ']' || c.is_whitespace()).to_string());
    connect_after_pair(deps, &svc.ip, guid.as_deref(), cancel)
}

/// Pairing-dən sonra telefona qoşulmaq. adb çox vaxt avtomatik qoşulur;
/// qoşulmasa, `_adb-tls-connect._tcp` xidmətini tapıb `adb connect` edirik.
pub fn connect_after_pair(
    deps: &PairingDeps,
    ip: &str,
    guid: Option<&str>,
    cancel: &AtomicBool,
) -> Result<String, String> {
    let deadline = Instant::now() + Duration::from_secs(25);
    let mut last_err = crate::i18n::t("pair.no_connect_service");
    while Instant::now() < deadline {
        if cancel.load(Ordering::SeqCst) {
            return Err(crate::i18n::t("pair.cancelled"));
        }
        // 1) adb özü qoşubmu?
        if let Ok(devices) = deps.adb.devices() {
            if let Some(d) = devices.iter().find(|d| {
                d.state == "device"
                    && (d.ip.as_deref() == Some(ip)
                        || guid.map(|g| d.serial.starts_with(g)).unwrap_or(false))
            }) {
                save_wifi_device(deps, &d.serial, ip, guid);
                return Ok(d.serial.clone());
            }
        }
        // 2) Özümüz qoşulaq
        let svc = guid
            .and_then(|g| deps.mdns.find_connect_by_instance(&deps.adb, g))
            .or_else(|| deps.mdns.find_connect_by_ip(&deps.adb, ip));
        if let Some(svc) = svc {
            match deps.adb.connect(&svc.addr(), Duration::from_secs(10)) {
                Ok(_) => {
                    save_wifi_device(deps, &svc.addr(), ip, guid.or(Some(&svc.instance)));
                    return Ok(svc.addr());
                }
                Err(e) => last_err = e,
            }
        }
        thread::sleep(Duration::from_millis(800));
    }
    Err(last_err)
}

fn save_wifi_device(deps: &PairingDeps, serial: &str, ip: &str, guid: Option<&str>) {
    let name = {
        let manufacturer = deps.adb.getprop(serial, "ro.product.manufacturer");
        let model = deps.adb.getprop(serial, "ro.product.model");
        match (manufacturer, model) {
            (Some(m), Some(md)) if !md.to_lowercase().starts_with(&m.to_lowercase()) => format!("{m} {md}"),
            (_, Some(md)) => md,
            _ => ip.to_string(),
        }
    };
    let port = serial
        .rsplit_once(':')
        .and_then(|(_, p)| p.parse().ok())
        .unwrap_or(5555);
    deps.store.upsert(SavedDevice {
        id: guid.map(str::to_string).unwrap_or_else(|| ip.to_string()),
        name,
        ip: ip.to_string(),
        port,
        mdns_instance: guid.map(str::to_string),
        auto_reconnect: true,
        last_seen: 0,
    });
}

/// 6 rəqəmli kod ilə əl ilə pairing ("Pair device with pairing code").
pub fn pair_with_code(deps: &PairingDeps, host: &str, code: &str) -> Result<String, String> {
    let host = host.trim();
    let code = code.trim();
    let (ip, port) = host
        .rsplit_once(':')
        .ok_or_else(|| crate::i18n::t("pair.addr_format"))?;
    if port.parse::<u16>().is_err() {
        return Err(crate::i18n::t("pair.port_number"));
    }
    if code.len() != 6 || !code.chars().all(|c| c.is_ascii_digit()) {
        return Err(crate::i18n::t("pair.code_digits"));
    }
    let paired = deps.adb.pair(host, code)?;
    let guid = paired
        .split("guid=")
        .nth(1)
        .map(|s| s.trim_end_matches(|c: char| c == ']' || c.is_whitespace()).to_string());
    let never = AtomicBool::new(false);
    connect_after_pair(deps, ip, guid.as_deref(), &never)
}

/// IP:port ilə birbaşa qoşulma (artıq pair olunmuş və ya tcpip rejimli cihaz).
pub fn connect_address(deps: &PairingDeps, addr: &str) -> Result<String, String> {
    let addr = addr.trim();
    let addr = if addr.contains(':') { addr.to_string() } else { format!("{addr}:5555") };
    deps.adb.connect(&addr, Duration::from_secs(10))?;
    let ip = addr.rsplit_once(':').map(|(i, _)| i).unwrap_or(&addr).to_string();
    save_wifi_device(deps, &addr, &ip, None);
    Ok(addr)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn payload_format() {
        assert_eq!(qr_payload("easyconnect-ab", "pw"), "WIFI:T:ADB;S:easyconnect-ab;P:pw;;");
    }

    #[test]
    fn renders_svg() {
        let svg = render_qr(&qr_payload("easyconnect-ab", "pw")).unwrap();
        assert!(svg.contains("<svg"));
    }
}
