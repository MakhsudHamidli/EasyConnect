//! Proqramın içinə qoyulmuş adb və scrcpy fayllarını tapmaq.

use std::path::{Path, PathBuf};
use tauri::{AppHandle, Manager};

#[derive(Debug, Clone)]
pub struct Tools {
    pub dir: PathBuf,
    pub adb: PathBuf,
    pub scrcpy: PathBuf,
    pub server: PathBuf,
}

fn exe(name: &str) -> String {
    if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.to_string()
    }
}

fn platform_dir() -> &'static str {
    if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    }
}

/// Windows `resource_dir()` yolu `\\?\C:\...` (verbatim) formatında qaytarır.
/// scrcpy/adb bu formatı başa düşmür ("stat: No such file or directory"),
/// ona görə adi `C:\...` formatına çeviririk. UNC yolları (`\\?\UNC\server\share`) da düzəldilir.
fn normalize(dir: &Path) -> PathBuf {
    let s = dir.to_string_lossy();
    if let Some(rest) = s.strip_prefix(r"\\?\UNC\") {
        PathBuf::from(format!(r"\\{rest}"))
    } else if let Some(rest) = s.strip_prefix(r"\\?\") {
        PathBuf::from(rest)
    } else {
        dir.to_path_buf()
    }
}

/// macOS/Linux: quraşdırıcı icra hüququnu itirərsə, bərpa edirik (məzmun dəyişmir).
#[cfg(unix)]
fn ensure_executable(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    if let Ok(meta) = std::fs::metadata(path) {
        let mode = meta.permissions().mode();
        if mode & 0o111 == 0 {
            let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode | 0o755));
        }
    }
}

#[cfg(not(unix))]
fn ensure_executable(_path: &Path) {}

fn tools_in(dir: &Path) -> Option<Tools> {
    let dir = &normalize(dir);
    let adb = dir.join(exe("adb"));
    let scrcpy = dir.join(exe("scrcpy"));
    let server = dir.join("scrcpy-server");
    if adb.is_file() && scrcpy.is_file() {
        ensure_executable(&adb);
        ensure_executable(&scrcpy);
    }
    (adb.is_file() && scrcpy.is_file()).then(|| Tools {
        dir: dir.to_path_buf(),
        adb,
        scrcpy,
        server,
    })
}

/// Axtarış ardıcıllığı:
/// 1. `EASYCONNECT_SCRCPY_DIR` mühit dəyişəni
/// 2. Quraşdırılmış proqramın resurs qovluğu (`<resources>/scrcpy`)
/// 3. İnkişaf rejimində mənbə qovluğu (`src-tauri/resources/scrcpy/<os>`)
/// 4. Proqramın exe faylının yanı
pub fn locate(app: &AppHandle) -> Result<Tools, String> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(dir) = std::env::var("EASYCONNECT_SCRCPY_DIR") {
        candidates.push(PathBuf::from(dir));
    }
    if let Ok(res) = app.path().resource_dir() {
        candidates.push(res.join("scrcpy"));
    }
    if cfg!(debug_assertions) {
        candidates.push(
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("resources")
                .join("scrcpy")
                .join(platform_dir()),
        );
    }
    if let Some(dir) = std::env::current_exe().ok().and_then(|p| p.parent().map(Path::to_path_buf)) {
        candidates.push(dir.join("scrcpy"));
        candidates.push(dir);
    }

    candidates
        .iter()
        .find_map(|d| tools_in(d))
        .ok_or_else(|| {
            let searched: Vec<String> = candidates.iter().map(|p| p.display().to_string()).collect();
            crate::i18n::tf("tools.not_found", &[("dirs", &searched.join("\n"))])
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_verbatim_prefix() {
        assert_eq!(normalize(Path::new(r"\\?\C:\a\b")), PathBuf::from(r"C:\a\b"));
        assert_eq!(normalize(Path::new(r"\\?\UNC\srv\share\x")), PathBuf::from(r"\\srv\share\x"));
        assert_eq!(normalize(Path::new(r"C:\a")), PathBuf::from(r"C:\a"));
    }
}
