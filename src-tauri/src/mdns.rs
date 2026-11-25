//! Şəbəkədə Wireless debugging xidmətlərini tapmaq.
//!
//! İki mənbə birlikdə işləyir:
//! * öz mDNS axtarışımız (`mdns-sd` crate), adb-dən asılı deyil;
//! * `adb mdns services`, adb-nin daxili axtarışı.
//! Hansı birincisi tapsa, o istifadə olunur.

use crate::adb::{Adb, MdnsService, CONNECT_SERVICE, PAIRING_SERVICE};
use mdns_sd::{ServiceDaemon, ServiceEvent};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::thread;

#[derive(Clone, Default)]
pub struct MdnsCache {
    // (instance, service_type) -> service
    inner: Arc<Mutex<HashMap<(String, String), MdnsService>>>,
}

impl MdnsCache {
    fn upsert(&self, svc: MdnsService) {
        let key = (svc.instance.clone(), svc.service_type.clone());
        self.inner.lock().unwrap().insert(key, svc);
    }

    fn remove(&self, instance: &str, service_type: &str) {
        self.inner
            .lock()
            .unwrap()
            .remove(&(instance.to_string(), service_type.to_string()));
    }

    fn snapshot(&self) -> Vec<MdnsService> {
        self.inner.lock().unwrap().values().cloned().collect()
    }

    /// Hər iki mənbədən birləşdirilmiş siyahı.
    pub fn services(&self, adb: &Adb) -> Vec<MdnsService> {
        let mut all = self.snapshot();
        for s in adb.mdns_services() {
            if !all
                .iter()
                .any(|x| x.instance == s.instance && x.service_type == s.service_type)
            {
                all.push(s);
            }
        }
        all
    }

    pub fn find_pairing(&self, adb: &Adb, instance: &str) -> Option<MdnsService> {
        self.services(adb)
            .into_iter()
            .find(|s| s.service_type == PAIRING_SERVICE && s.instance == instance)
    }

    pub fn find_connect_by_ip(&self, adb: &Adb, ip: &str) -> Option<MdnsService> {
        self.services(adb)
            .into_iter()
            .find(|s| s.service_type == CONNECT_SERVICE && s.ip == ip)
    }

    pub fn find_connect_by_instance(&self, adb: &Adb, instance: &str) -> Option<MdnsService> {
        self.services(adb)
            .into_iter()
            .find(|s| s.service_type == CONNECT_SERVICE && s.instance == instance)
    }
}

/// Fon axtarışını başladır. Uğursuz olarsa (məs. firewall), yalnız adb mənbəyi qalır.
pub fn start_browser(cache: MdnsCache) {
    let daemon = match ServiceDaemon::new() {
        Ok(d) => d,
        Err(e) => {
            eprintln!("[mdns] daemon başladıla bilmədi: {e}");
            return;
        }
    };
    for service in [PAIRING_SERVICE, CONNECT_SERVICE] {
        let full = format!("{service}.local.");
        let receiver = match daemon.browse(&full) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("[mdns] {service} axtarışı başladıla bilmədi: {e}");
                continue;
            }
        };
        let cache = cache.clone();
        let suffix = format!(".{full}");
        thread::spawn(move || {
            while let Ok(event) = receiver.recv() {
                match event {
                    ServiceEvent::ServiceResolved(info) => {
                        let fullname = info.get_fullname().to_string();
                        let instance = fullname.strip_suffix(&suffix).unwrap_or(&fullname).to_string();
                        let ip = info
                            .get_addresses()
                            .iter()
                            .map(|a| a.to_string())
                            .filter_map(|a| a.parse::<IpAddr>().ok())
                            .find(IpAddr::is_ipv4)
                            .or_else(|| {
                                info.get_addresses()
                                    .iter()
                                    .filter_map(|a| a.to_string().parse::<IpAddr>().ok())
                                    .next()
                            });
                        if let Some(ip) = ip {
                            cache.upsert(MdnsService {
                                instance,
                                service_type: service.to_string(),
                                ip: ip.to_string(),
                                port: info.get_port(),
                            });
                        }
                    }
                    ServiceEvent::ServiceRemoved(_, fullname) => {
                        let instance = fullname.strip_suffix(&suffix).unwrap_or(&fullname);
                        cache.remove(instance, service);
                    }
                    _ => {}
                }
            }
        });
    }
    // daemon proqram bağlanana qədər yaşamalıdır
    std::mem::forget(daemon);
}
