//! Yadda saxlanmış Wi-Fi cihazları (avtomatik yenidən qoşulma üçün).

use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SavedDevice {
    /// Sabit açar: mDNS adı (adb-<guid>) bilinirsə o, yoxsa IP.
    pub id: String,
    pub name: String,
    pub ip: String,
    pub port: u16,
    #[serde(default)]
    pub mdns_instance: Option<String>,
    #[serde(default = "yes")]
    pub auto_reconnect: bool,
    #[serde(default)]
    pub last_seen: u64,
}

fn yes() -> bool {
    true
}

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub struct Store {
    path: PathBuf,
    devices: Mutex<Vec<SavedDevice>>,
}

impl Store {
    pub fn load(path: PathBuf) -> Self {
        let devices = std::fs::read_to_string(&path)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        Self {
            path,
            devices: Mutex::new(devices),
        }
    }

    fn persist(&self, devices: &[SavedDevice]) {
        if let Some(dir) = self.path.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        if let Ok(json) = serde_json::to_string_pretty(devices) {
            let _ = std::fs::write(&self.path, json);
        }
    }

    pub fn list(&self) -> Vec<SavedDevice> {
        self.devices.lock().unwrap().clone()
    }

    /// Eyni IP və ya eyni mDNS adı olan qeydi yeniləyir, yoxdursa əlavə edir.
    pub fn upsert(&self, mut dev: SavedDevice) {
        let mut list = self.devices.lock().unwrap();
        dev.last_seen = now();
        if let Some(existing) = list.iter_mut().find(|d| {
            d.id == dev.id
                || d.ip == dev.ip
                || (dev.mdns_instance.is_some() && d.mdns_instance == dev.mdns_instance)
        }) {
            let auto = existing.auto_reconnect;
            let mdns = existing.mdns_instance.clone();
            *existing = dev;
            existing.auto_reconnect = auto;
            if existing.mdns_instance.is_none() {
                existing.mdns_instance = mdns;
            }
        } else {
            list.push(dev);
        }
        self.persist(&list);
    }

    pub fn forget(&self, id: &str) {
        let mut list = self.devices.lock().unwrap();
        list.retain(|d| d.id != id);
        self.persist(&list);
    }

    pub fn set_auto_reconnect(&self, id: &str, value: bool) {
        let mut list = self.devices.lock().unwrap();
        if let Some(d) = list.iter_mut().find(|d| d.id == id) {
            d.auto_reconnect = value;
        }
        self.persist(&list);
    }
}
