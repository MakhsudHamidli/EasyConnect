//! Kompüterin TLS kimliyi: ilk açılışda yaradılan self-signed sertifikat.
//! Telefon sertifikatın SHA-256 barmaq izini QR-dan alır və yalnız ona etibar edir
//! (certificate pinning), ona görə şəbəkədəki başqa cihaz özünü kompüter kimi göstərə bilməz.

use ring::digest;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use std::path::Path;

pub struct Identity {
    pub cert: CertificateDer<'static>,
    pub key: PrivateKeyDer<'static>,
    /// Kiçik hərflərlə hex SHA-256 (64 simvol)
    pub fingerprint: String,
}

pub fn fingerprint(der: &[u8]) -> String {
    digest::digest(&digest::SHA256, der)
        .as_ref()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

pub fn load_or_create(dir: &Path) -> Result<Identity, String> {
    let cert_path = dir.join("easy-cert.der");
    let key_path = dir.join("easy-key.der");
    if let (Ok(cert), Ok(key)) = (std::fs::read(&cert_path), std::fs::read(&key_path)) {
        let fp = fingerprint(&cert);
        return Ok(Identity {
            cert: CertificateDer::from(cert),
            key: PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)),
            fingerprint: fp,
        });
    }
    let generated = rcgen::generate_simple_self_signed(vec!["easyconnect.local".to_string()])
        .map_err(|e| crate::i18n::tf("easy.cert_failed", &[("e", &e.to_string())]))?;
    let cert = generated.cert.der().to_vec();
    let key = generated.key_pair.serialize_der();
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    std::fs::write(&cert_path, &cert).map_err(|e| e.to_string())?;
    std::fs::write(&key_path, &key).map_err(|e| e.to_string())?;
    restrict_permissions(&key_path);
    let fp = fingerprint(&cert);
    Ok(Identity {
        cert: CertificateDer::from(cert),
        key: PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)),
        fingerprint: fp,
    })
}

#[cfg(unix)]
fn restrict_permissions(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
}

#[cfg(not(unix))]
fn restrict_permissions(_path: &Path) {
    // Windows-da %APPDATA% artıq yalnız istifadəçinin özünə açıqdır.
}
