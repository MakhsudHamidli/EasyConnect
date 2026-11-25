//! adb əmrləri üçün nazik qat: cihaz siyahısı, pairing, connect, tcpip.

use crate::errors;
use crate::proc::{self, Output};
use serde::Serialize;
use std::path::PathBuf;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct Adb {
    path: PathBuf,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Device {
    pub serial: String,
    /// device | unauthorized | offline | no permissions | ...
    pub state: String,
    /// usb | wifi | emulator
    pub transport: String,
    pub model: Option<String>,
    pub product: Option<String>,
    /// Wi-Fi ilə qoşulmuş cihazın IP ünvanı (bilinirsə)
    pub ip: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "camelCase")]
pub struct MdnsService {
    pub instance: String,
    /// `_adb-tls-pairing._tcp` və ya `_adb-tls-connect._tcp`
    pub service_type: String,
    pub ip: String,
    pub port: u16,
}

impl MdnsService {
    pub fn addr(&self) -> String {
        format!("{}:{}", self.ip, self.port)
    }
}

pub const PAIRING_SERVICE: &str = "_adb-tls-pairing._tcp";
pub const CONNECT_SERVICE: &str = "_adb-tls-connect._tcp";

impl Adb {
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }

    pub fn exec(&self, args: &[&str], timeout: Duration) -> Result<Output, String> {
        let mut cmd = proc::command(&self.path);
        cmd.args(args);
        proc::run_with_timeout(cmd, timeout)
    }

    /// Uğurlu olarsa çıxışı qaytarır, əks halda izah olunmuş xəta.
    pub fn run(&self, args: &[&str], timeout: Duration) -> Result<String, String> {
        let out = self.exec(args, timeout)?;
        let text = out.combined();
        if out.success {
            Ok(text)
        } else {
            Err(errors::explain(&text))
        }
    }

    pub fn version(&self) -> Result<String, String> {
        let out = self.run(&["version"], Duration::from_secs(10))?;
        Ok(out
            .lines()
            .find(|l| l.starts_with("Version"))
            .unwrap_or_else(|| out.lines().next().unwrap_or(""))
            .trim()
            .to_string())
    }

    pub fn start_server(&self) -> Result<(), String> {
        self.run(&["start-server"], Duration::from_secs(30)).map(|_| ())
    }

    pub fn devices(&self) -> Result<Vec<Device>, String> {
        let out = self.run(&["devices", "-l"], Duration::from_secs(10))?;
        Ok(parse_devices(&out))
    }

    pub fn getprop(&self, serial: &str, prop: &str) -> Option<String> {
        self.run(&["-s", serial, "shell", "getprop", prop], Duration::from_secs(5))
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
    }

    /// `adb pair ip:port kod`
    pub fn pair(&self, addr: &str, code: &str) -> Result<String, String> {
        let out = self.exec(&["pair", addr, code], Duration::from_secs(30))?;
        let text = out.combined();
        if text.contains("Successfully paired") {
            Ok(text)
        } else {
            Err(errors::explain(&text))
        }
    }

    /// `adb connect ip:port` — adb uğursuzluqda da 0 qaytarır, ona görə mətnə baxırıq.
    pub fn connect(&self, addr: &str, timeout: Duration) -> Result<String, String> {
        let out = self.exec(&["connect", addr], timeout)?;
        let text = out.combined();
        if text.contains("connected to") {
            Ok(text)
        } else {
            Err(errors::explain(&text))
        }
    }

    pub fn disconnect(&self, serial: &str) -> Result<String, String> {
        self.run(&["disconnect", serial], Duration::from_secs(10))
    }

    /// `adb mdns services` çıxışı.
    pub fn mdns_services(&self) -> Vec<MdnsService> {
        match self.run(&["mdns", "services"], Duration::from_secs(5)) {
            Ok(out) => parse_mdns(&out),
            Err(_) => Vec::new(),
        }
    }

    /// Telefonun Wi-Fi IP ünvanını tapır (`ip route` çıxışından).
    pub fn device_wifi_ip(&self, serial: &str) -> Option<String> {
        let out = self
            .run(&["-s", serial, "shell", "ip", "route"], Duration::from_secs(5))
            .ok()?;
        // Nümunə: "192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.42"
        let pick = |line: &str| -> Option<String> {
            let mut parts = line.split_whitespace();
            while let Some(p) = parts.next() {
                if p == "src" {
                    return parts.next().map(str::to_string);
                }
            }
            None
        };
        pick_local_ip(&out, pick)
    }

    /// USB ilə qoşulmuş cihazı Wi-Fi rejiminə keçirir və qoşulur. Yeni serial-ı qaytarır.
    pub fn switch_to_wifi(&self, serial: &str) -> Result<String, String> {
        let ip = self.device_wifi_ip(serial).ok_or_else(|| crate::i18n::t("adb.no_local_ip"))?;
        self.run(&["-s", serial, "tcpip", "5555"], Duration::from_secs(10))?;
        std::thread::sleep(Duration::from_millis(1500));
        let addr = format!("{ip}:5555");
        for _ in 0..3 {
            if self.connect(&addr, Duration::from_secs(5)).is_ok() {
                return Ok(addr);
            }
            std::thread::sleep(Duration::from_millis(1000));
        }
        // Ən çox rast gəlinən səbəb: kompüter telefonun şəbəkəsində deyil.
        Err(crate::i18n::tf("adb.unreachable", &[("ip", &ip), ("addr", &addr)]))
    }
}

/// `ip route` çıxışından kompüterin çata biləcəyi lokal IP-ni seçir.
/// Ardıcıllıq: Wi-Fi (`wlan*`), sonra telefonun öz hotspotu (`ap*`, `swlan*`, `softap*`),
/// sonra USB/Bluetooth tethering. Mobil internet (`rmnet*`, `ccmni*`) heç vaxt seçilmir,
/// çünki operator şəbəkəsindən kompüter telefona birbaşa çata bilməz.
fn pick_local_ip(out: &str, pick: impl Fn(&str) -> Option<String>) -> Option<String> {
    let dev_of = |line: &str| -> Option<String> {
        let mut parts = line.split_whitespace();
        while let Some(p) = parts.next() {
            if p == "dev" {
                return parts.next().map(str::to_string);
            }
        }
        None
    };
    let groups: [&[&str]; 3] = [&["wlan"], &["ap", "swlan", "softap", "wifi"], &["rndis", "usb", "bt-pan", "ncm"]];
    for prefixes in groups {
        if let Some(ip) = out
            .lines()
            .filter(|l| dev_of(l).map(|d| prefixes.iter().any(|p| d.starts_with(p))).unwrap_or(false))
            .find_map(&pick)
        {
            return Some(ip);
        }
    }
    None
}

pub fn parse_devices(out: &str) -> Vec<Device> {
    out.lines()
        .filter(|l| !l.trim().is_empty() && !l.starts_with("List of devices") && !l.starts_with('*'))
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let serial = parts.next()?.to_string();
            let mut rest: Vec<&str> = parts.collect();
            // vəziyyət "no permissions" kimi iki sözdən ibarət ola bilər
            let mut state_words = Vec::new();
            while let Some(first) = rest.first() {
                if first.contains(':') {
                    break;
                }
                state_words.push(rest.remove(0));
            }
            let state = if state_words.is_empty() {
                "unknown".to_string()
            } else {
                state_words.join(" ")
            };
            let kv = |key: &str| {
                rest.iter()
                    .find_map(|p| p.strip_prefix(&format!("{key}:")))
                    .map(|v| v.replace('_', " "))
            };
            let (transport, ip) = classify(&serial);
            Some(Device {
                serial,
                state,
                transport,
                model: kv("model"),
                product: kv("product"),
                ip,
            })
        })
        .collect()
}

fn classify(serial: &str) -> (String, Option<String>) {
    if serial.starts_with("emulator-") {
        return ("emulator".into(), None);
    }
    if serial.contains("._adb-tls-connect._tcp") {
        return ("wifi".into(), None);
    }
    if let Some((host, port)) = serial.rsplit_once(':') {
        if port.parse::<u16>().is_ok() {
            return ("wifi".into(), Some(host.to_string()));
        }
    }
    ("usb".into(), None)
}

pub fn parse_mdns(out: &str) -> Vec<MdnsService> {
    out.lines()
        .filter_map(|line| {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() < 3 || !parts[1].starts_with("_adb") {
                return None;
            }
            let service_type = parts[1].trim_end_matches('.').to_string();
            let (ip, port) = parts[2].rsplit_once(':')?;
            Some(MdnsService {
                instance: parts[0].to_string(),
                service_type,
                ip: ip.to_string(),
                port: port.parse().ok()?,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_devices() {
        let out = "List of devices attached\n\
R58M12345ABC           device usb:1-1 product:beyond1lteeea model:SM_G973F device:beyond1 transport_id:1\n\
192.168.1.5:5555       device product:panther model:Pixel_7 device:panther transport_id:3\n\
adb-XYZ-abc._adb-tls-connect._tcp device product:p model:Pixel_8 device:shiba transport_id:5\n\
ABCDEF unauthorized usb:1-2 transport_id:4\n\
QWERTY no permissions (user in plugdev group) usb:1-3 transport_id:6\n";
        let d = parse_devices(out);
        assert_eq!(d.len(), 5);
        assert_eq!(d[0].transport, "usb");
        assert_eq!(d[0].model.as_deref(), Some("SM G973F"));
        assert_eq!(d[1].transport, "wifi");
        assert_eq!(d[1].ip.as_deref(), Some("192.168.1.5"));
        assert_eq!(d[2].transport, "wifi");
        assert_eq!(d[3].state, "unauthorized");
        assert!(d[4].state.starts_with("no permissions"));
    }

    #[test]
    fn picks_hotspot_ip_not_mobile_data() {
        let src = |line: &str| -> Option<String> {
            let mut it = line.split_whitespace();
            while let Some(p) = it.next() {
                if p == "src" {
                    return it.next().map(str::to_string);
                }
            }
            None
        };
        let hotspot = "10.12.0.0/16 dev rmnet_data0 proto kernel scope link src 10.12.4.7\n\
192.168.43.0/24 dev ap0 proto kernel scope link src 192.168.43.1";
        assert_eq!(pick_local_ip(hotspot, src).as_deref(), Some("192.168.43.1"));
        let wifi = "192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.42";
        assert_eq!(pick_local_ip(wifi, src).as_deref(), Some("192.168.1.42"));
        let mobile_only = "10.12.0.0/16 dev rmnet_data0 proto kernel scope link src 10.12.4.7";
        assert_eq!(pick_local_ip(mobile_only, src), None);
    }

    #[test]
    fn parses_mdns() {
        let out = "List of discovered mdns services\n\
adb-R58M-AbCd\t_adb-tls-connect._tcp\t192.168.1.5:41235\n\
easyconnect-x1\t_adb-tls-pairing._tcp.\t192.168.1.5:37555\n";
        let s = parse_mdns(out);
        assert_eq!(s.len(), 2);
        assert_eq!(s[1].service_type, PAIRING_SERVICE);
        assert_eq!(s[1].port, 37555);
    }
}
