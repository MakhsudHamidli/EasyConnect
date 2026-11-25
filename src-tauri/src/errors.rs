//! adb/scrcpy səhv mesajlarını istifadəçinin başa düşəcəyi dilə çevirir.

struct Rule {
    needles: &'static [&'static str],
    /// i18n açarı
    message: &'static str,
}

const RULES: &[Rule] = &[
    Rule {
        needles: &["Device disconnected"],
        message: "err.disconnected",
    },
    Rule {
        needles: &["unauthorized", "device unauthorized"],
        message: "err.unauthorized",
    },
    Rule {
        needles: &["Wrong password", "wrong password"],
        message: "err.wrong_code",
    },
    Rule {
        needles: &["INJECT_EVENTS", "Injecting input events requires"],
        message: "err.inject",
    },
    Rule {
        needles: &["Could not find any ADB device", "no devices/emulators found", "device not found"],
        message: "err.no_device",
    },
    Rule {
        needles: &["Multiple", "more than one device"],
        message: "err.multiple",
    },
    Rule {
        needles: &["Connection refused", "cannot connect to", "failed to connect", "No connection could be made"],
        message: "err.connect_failed",
    },
    Rule {
        needles: &["failed to authenticate"],
        message: "err.auth_failed",
    },
    Rule {
        needles: &["timed out", "Operation timed out", "vaxtı bitdi"],
        message: "err.timeout",
    },
    Rule {
        needles: &["offline"],
        message: "err.offline",
    },
    Rule {
        needles: &["Could not open video stream", "Could not create video encoder", "Encoder not supported", "MediaCodec"],
        message: "err.encoder",
    },
    Rule {
        needles: &["Audio disabled", "audio capture", "Audio not supported"],
        message: "err.audio",
    },
    Rule {
        needles: &["adb server version", "doesn't match this client"],
        message: "err.adb_version",
    },
    Rule {
        needles: &["no permissions"],
        message: "err.no_permissions",
    },
];

/// Mətndə tanınan səhv varsa, izahını qaytarır; yoxdursa orijinal mətni.
pub fn explain(raw: &str) -> String {
    explain_known(raw).unwrap_or_else(|| {
        let t = raw.trim();
        if t.is_empty() {
            crate::i18n::t("err.unknown")
        } else {
            t.to_string()
        }
    })
}

pub fn explain_known(raw: &str) -> Option<String> {
    RULES
        .iter()
        .find(|r| r.needles.iter().any(|n| raw.contains(n)))
        .map(|r| crate::i18n::t(r.message))
}

/// Windows USB driver problemi ehtimal edilirsə, köməkçi link.
pub const USB_DRIVER_URL: &str = "https://developer.android.com/studio/run/oem-usb";
