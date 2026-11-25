//! Fayl ötürmə (telefonun sahibi "Fayllar" icazəsi veribsə).
//!
//! Telefondan gələn fayllar YALNIZ `Downloads/EasyConnect` qovluğuna yazılır:
//! ad təmizlənir (yol hissələri, gizli fayl nöqtəsi, qadağan simvollar), mövcud fayl
//! üzərinə yazılmır, ölçü həddi 4 GB-dır.

use serde::Serialize;
use std::collections::HashMap;
use std::fs::File;
use std::io::Write;
use std::path::{Path, PathBuf};

pub const MAX_SIZE: u64 = 4 * 1024 * 1024 * 1024;
pub const CHUNK: usize = 256 * 1024;
/// Kompüterdən göndərilən faylların id-ləri bu ədəddən başlayır (telefonun id-ləri ilə qarışmasın)
pub const OUTGOING_ID_BASE: u32 = 0x4000_0000;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEvent {
    /// sessiya id
    pub id: String,
    /// "in" (telefondan) | "out" (telefona)
    pub dir: &'static str,
    pub name: String,
    pub done: bool,
    pub ok: bool,
    pub bytes: u64,
    pub size: i64,
    pub path: Option<String>,
    pub reason: Option<String>,
}

pub fn sanitize(raw: &str) -> String {
    let base = raw.rsplit(['/', '\\']).next().unwrap_or("");
    let clean: String = base
        .chars()
        .filter(|c| !c.is_control() && !"<>:\"|?*".contains(*c))
        .collect();
    let clean = clean.trim().trim_start_matches('.').trim_end_matches(['.', ' ']);
    let clean: String = clean.chars().take(120).collect();
    if clean.is_empty() {
        "file".into()
    } else {
        clean
    }
}

/// `ad.txt` mövcuddursa `ad (1).txt`, `ad (2).txt` ...
pub fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let (stem, ext) = match name.rsplit_once('.') {
        Some((s, e)) if !s.is_empty() => (s.to_string(), format!(".{e}")),
        _ => (name.to_string(), String::new()),
    };
    (1..10_000)
        .map(|n| dir.join(format!("{stem} ({n}){ext}")))
        .find(|p| !p.exists())
        .unwrap_or_else(|| dir.join(format!("{stem}-{}{ext}", std::process::id())))
}

struct Incoming {
    file: File,
    path: PathBuf,
    name: String,
    size: i64,
    written: u64,
}

/// Telefondan gələn fayllar (bir sessiya üçün).
pub struct FilesIn {
    dir: PathBuf,
    map: HashMap<u32, Incoming>,
}

impl FilesIn {
    pub fn new(dir: PathBuf) -> Self {
        Self { dir, map: HashMap::new() }
    }

    pub fn begin(&mut self, id: u32, name: &str, size: i64) -> Result<String, String> {
        if size > MAX_SIZE as i64 {
            return Err("too_large".into());
        }
        std::fs::create_dir_all(&self.dir).map_err(|e| e.to_string())?;
        let name = sanitize(name);
        let path = unique_path(&self.dir, &name);
        let file = File::create(&path).map_err(|e| e.to_string())?;
        let shown = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or(name);
        self.map.insert(id, Incoming { file, path, name: shown.clone(), size, written: 0 });
        Ok(shown)
    }

    /// (ad, yazılan, ölçü) — irəliləyiş üçün
    pub fn data(&mut self, id: u32, bytes: &[u8]) -> Result<(String, u64, i64), String> {
        let f = self.map.get_mut(&id).ok_or("unknown")?;
        let limit = if f.size >= 0 { f.size as u64 } else { MAX_SIZE };
        if f.written + bytes.len() as u64 > limit {
            let f = self.map.remove(&id).unwrap();
            drop(f.file);
            let _ = std::fs::remove_file(&f.path);
            return Err("size_mismatch".into());
        }
        f.file.write_all(bytes).map_err(|e| e.to_string())?;
        f.written += bytes.len() as u64;
        Ok((f.name.clone(), f.written, f.size))
    }

    /// Uğurlu olarsa faylın yolu; uğursuz olarsa yarımçıq fayl silinir.
    pub fn end(&mut self, id: u32, ok: bool) -> Option<(String, Result<PathBuf, String>)> {
        let f = self.map.remove(&id)?;
        let complete = ok && (f.size < 0 || f.written == f.size as u64);
        drop(f.file);
        if complete {
            Some((f.name, Ok(f.path)))
        } else {
            let _ = std::fs::remove_file(&f.path);
            Some((f.name, Err("incomplete".into())))
        }
    }

    /// Sessiya bitəndə yarımçıq faylları silir.
    pub fn abort_all(&mut self) {
        for (_, f) in self.map.drain() {
            drop(f.file);
            let _ = std::fs::remove_file(&f.path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_cannot_escape_folder() {
        assert_eq!(sanitize("../../etc/passwd"), "passwd");
        assert_eq!(sanitize("C:\\Windows\\System32\\evil.dll"), "evil.dll");
        assert_eq!(sanitize(".bashrc"), "bashrc");
        assert_eq!(sanitize("a<b>c:d.txt"), "abcd.txt");
        assert_eq!(sanitize("..."), "file");
        assert_eq!(sanitize("photo.jpg"), "photo.jpg");
    }

    #[test]
    fn unique_names_and_size_limit() {
        let dir = std::env::temp_dir().join(format!("ec-files-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let mut fin = FilesIn::new(dir.clone());
        assert_eq!(fin.begin(1, "a.txt", 3).unwrap(), "a.txt");
        fin.data(1, b"abc").unwrap();
        let (_, res) = fin.end(1, true).unwrap();
        assert!(res.is_ok());
        assert_eq!(fin.begin(2, "a.txt", 2).unwrap(), "a (1).txt");
        assert!(fin.data(2, b"toolong").is_err()); // elan edilən ölçüdən çox
        assert!(!dir.join("a (1).txt").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
