//! Rust tərəfindən istifadəçiyə göstərilən mesajların tərcüməsi (az / ru / en).
//! Dil interfeysdən `set_language` əmri ilə təyin olunur.

use std::sync::atomic::{AtomicU8, Ordering};

static LANG: AtomicU8 = AtomicU8::new(0);

pub fn set(lang: &str) {
    let v = match lang {
        "ru" => 1,
        "en" => 2,
        _ => 0,
    };
    LANG.store(v, Ordering::Relaxed);
}

/// Tərcümə; `{name}` yer tutucuları `args` ilə əvəzlənir.
pub fn tf(key: &str, args: &[(&str, &str)]) -> String {
    let idx = LANG.load(Ordering::Relaxed) as usize;
    let mut s = TABLE
        .iter()
        .find(|row| row.0 == key)
        .map(|row| [row.1, row.2, row.3][idx].to_string())
        .unwrap_or_else(|| key.to_string());
    for (k, v) in args {
        s = s.replace(&format!("{{{k}}}"), v);
    }
    s
}

pub fn t(key: &str) -> String {
    tf(key, &[])
}

/// (açar, az, ru, en)
const TABLE: &[(&str, &str, &str, &str)] = &[
    // ---- adb / scrcpy səhvləri
    ("err.disconnected",
     "Telefonla əlaqə kəsildi. Kabelin bərk taxıldığını yoxlayın; Wi-Fi ilə qoşulubsunuzsa, telefonla kompüterin hələ də eyni şəbəkədə (və ya telefonun hotspotunda) olduğunu yoxlayın.",
     "Связь с телефоном потеряна. Проверьте кабель; при подключении по Wi-Fi убедитесь, что телефон и компьютер всё ещё в одной сети (или в точке доступа телефона).",
     "Connection to the phone was lost. Check the cable; if connected over Wi-Fi, make sure the phone and computer are still on the same network (or the phone's hotspot)."),
    ("err.unauthorized",
     "Telefon bu kompüterə hələ icazə verməyib. Telefonun ekranında \"USB debugging-ə icazə verilsin?\" pəncərəsini \"Həmişə icazə ver\" seçərək təsdiqləyin.",
     "Телефон ещё не разрешил доступ этому компьютеру. Подтвердите на телефоне окно «Разрешить отладку по USB?», выбрав «Всегда разрешать».",
     "The phone hasn't authorized this computer yet. Confirm the \"Allow USB debugging?\" prompt on the phone and choose \"Always allow\"."),
    ("err.wrong_code",
     "Pairing kodu səhvdir. Telefonda göstərilən 6 rəqəmli kodu yenidən daxil edin.",
     "Неверный код сопряжения. Введите заново 6-значный код с экрана телефона.",
     "Wrong pairing code. Re-enter the 6-digit code shown on the phone."),
    ("err.inject",
     "Telefon idarə üçün icazə vermir. Xiaomi/Redmi/POCO-da Developer options-da \"USB debugging (Security settings)\" seçimini də yandırın (SIM kart və Mi hesabı tələb oluna bilər).",
     "Телефон не разрешает управление. На Xiaomi/Redmi/POCO включите в параметрах разработчика «Отладка по USB (настройки безопасности)» (может потребоваться SIM-карта и Mi-аккаунт).",
     "The phone doesn't allow control. On Xiaomi/Redmi/POCO also enable \"USB debugging (Security settings)\" in Developer options (may require a SIM card and Mi account)."),
    ("err.no_device",
     "Telefon tapılmadı. Kabelin data ötürdüyünü (yalnız şarj kabeli olmadığını) və USB debugging-in açıq olduğunu yoxlayın.",
     "Телефон не найден. Убедитесь, что кабель передаёт данные (не только зарядка) и что отладка по USB включена.",
     "Phone not found. Make sure the cable carries data (not charge-only) and USB debugging is on."),
    ("err.multiple",
     "Bir neçə cihaz qoşulub. Siyahıdan konkret cihazı seçin.",
     "Подключено несколько устройств. Выберите нужное в списке.",
     "Several devices are connected. Pick one from the list."),
    ("err.connect_failed",
     "Telefona qoşulmaq alınmadı. Telefonda Wireless debugging-in açıq olduğunu, telefonla kompüterin eyni Wi-Fi şəbəkəsində olduğunu yoxlayın. Wi-Fi dəyişəndə port da dəyişir, ona görə QR ilə yenidən qoşulmaq lazım ola bilər.",
     "Не удалось подключиться к телефону. Проверьте, что отладка по Wi-Fi включена и телефон с компьютером в одной сети. При смене Wi-Fi меняется порт — может понадобиться заново подключиться по QR.",
     "Couldn't connect to the phone. Check that Wireless debugging is on and the phone and computer are on the same Wi-Fi. The port changes when Wi-Fi changes, so you may need to pair again with the QR code."),
    ("err.auth_failed",
     "Telefon kompüteri tanımadı. Wireless debugging → Paired devices bölməsində bu kompüteri silib yenidən QR ilə qoşulun.",
     "Телефон не узнал компьютер. Удалите этот компьютер в «Отладка по Wi-Fi → Сопряжённые устройства» и подключитесь заново по QR.",
     "The phone didn't recognize this computer. Remove it under Wireless debugging → Paired devices and pair again with the QR code."),
    ("err.timeout",
     "Telefon cavab vermədi. Ekranın açıq olduğunu və eyni Wi-Fi şəbəkəsində olduğunuzu yoxlayın.",
     "Телефон не ответил. Проверьте, что экран включён и вы в одной сети Wi-Fi.",
     "The phone didn't respond. Make sure its screen is on and you're on the same Wi-Fi."),
    ("err.offline",
     "Cihaz \"offline\" vəziyyətindədir. Kabeli çıxarıb yenidən taxın və ya Wireless debugging-i söndürüb yandırın.",
     "Устройство в состоянии «offline». Переподключите кабель или выключите и включите отладку по Wi-Fi.",
     "The device is \"offline\". Reconnect the cable or toggle Wireless debugging off and on."),
    ("err.encoder",
     "Telefon video kodlayıcısı bu ayarları dəstəkləmir. \"Zəif Wi-Fi\" rejimini (kiçik ölçü) sınayın.",
     "Видеокодер телефона не поддерживает эти настройки. Попробуйте режим «Слабый Wi-Fi» (меньший размер).",
     "The phone's video encoder doesn't support these settings. Try the \"Weak Wi-Fi\" mode (smaller size)."),
    ("err.audio",
     "Səs ötürülməsi bu telefonda dəstəklənmir (Android 11+ lazımdır). Görüntü səssiz davam edir.",
     "Передача звука на этом телефоне не поддерживается (нужен Android 11+). Изображение продолжится без звука.",
     "Audio forwarding isn't supported on this phone (Android 11+ required). Video continues without sound."),
    ("err.adb_version",
     "Kompüterdə başqa adb versiyası işləyir (məsələn Android Studio). Onu bağlayıb yenidən cəhd edin.",
     "На компьютере запущена другая версия adb (например, Android Studio). Закройте её и попробуйте снова.",
     "Another adb version is running on this computer (e.g. Android Studio). Close it and try again."),
    ("err.no_permissions",
     "Sistem USB cihazına giriş icazəsi vermir. Linux-da udev qaydalarını yoxlayın.",
     "Система не даёт доступ к USB-устройству. В Linux проверьте правила udev.",
     "The system denies access to the USB device. On Linux, check your udev rules."),
    ("err.unknown", "Naməlum xəta baş verdi.", "Произошла неизвестная ошибка.", "An unknown error occurred."),
    // ---- alətlər / proseslər
    ("tools.not_found",
     "scrcpy və adb tapılmadı. Axtarılan qovluqlar:\n{dirs}",
     "scrcpy и adb не найдены. Проверенные папки:\n{dirs}",
     "scrcpy and adb were not found. Folders searched:\n{dirs}"),
    ("proc.spawn_failed", "Proses başladıla bilmədi: {e}", "Не удалось запустить процесс: {e}", "Failed to start process: {e}"),
    ("proc.timeout", "Əməliyyat vaxtı bitdi (cavab gəlmədi)", "Время операции истекло (нет ответа)", "Operation timed out (no response)"),
    ("proc.wait_failed", "Proses gözlənilərkən xəta: {e}", "Ошибка ожидания процесса: {e}", "Error while waiting for process: {e}"),
    // ---- Wi-Fi-a keçid
    ("adb.no_local_ip",
     "Telefonun lokal şəbəkə ünvanı tapılmadı. Telefonu kompüterlə eyni Wi-Fi-a qoşun, və ya telefonda Hotspot-u yandırıb kompüteri ona qoşun.",
     "Не найден локальный адрес телефона. Подключите телефон к той же сети Wi-Fi, что и компьютер, или включите точку доступа на телефоне и подключите к ней компьютер.",
     "Couldn't find the phone's local network address. Connect the phone to the same Wi-Fi as the computer, or turn on the phone's hotspot and connect the computer to it."),
    ("adb.unreachable",
     "Kompüter telefona ({ip}) şəbəkə ilə çata bilmir. Telefonla kompüter eyni Wi-Fi-da olmalıdır, və ya telefonda Hotspot-u yandırıb kompüteri ona qoşun. Sonra \"IP ilə\" bölməsində {addr} yazıb qoşulun (kabeli çıxarıb-taxsanız USB ilə də işləyəcək).",
     "Компьютер не может связаться с телефоном ({ip}) по сети. Телефон и компьютер должны быть в одной сети Wi-Fi, или включите точку доступа на телефоне и подключите к ней компьютер. Затем во вкладке «По IP» введите {addr} (после переподключения кабеля USB тоже будет работать).",
     "The computer can't reach the phone ({ip}) over the network. Put both on the same Wi-Fi, or turn on the phone's hotspot and connect the computer to it. Then enter {addr} in the \"By IP\" tab (re-plugging the cable also restores USB)."),
    // ---- QR / kod ilə pairing
    ("pair.qr_failed", "QR yaradıla bilmədi: {e}", "Не удалось создать QR: {e}", "Could not create QR: {e}"),
    ("pair.waiting", "Telefonda QR kodu skan edin…", "Отсканируйте QR-код на телефоне…", "Scan the QR code on your phone…"),
    ("pair.timeout", "Vaxt bitdi. Yeni QR yaradın və yenidən skan edin.", "Время истекло. Создайте новый QR и отсканируйте снова.", "Timed out. Create a new QR and scan again."),
    ("pair.found", "Telefon tapıldı ({ip}). Qoşulur…", "Телефон найден ({ip}). Подключение…", "Phone found ({ip}). Connecting…"),
    ("pair.connected", "Telefon Wi-Fi ilə qoşuldu!", "Телефон подключён по Wi-Fi!", "Phone connected over Wi-Fi!"),
    ("pair.no_connect_service",
     "Telefonun qoşulma xidməti tapılmadı. Wireless debugging-in açıq olduğunu yoxlayın.",
     "Служба подключения телефона не найдена. Проверьте, что отладка по Wi-Fi включена.",
     "The phone's connection service wasn't found. Check that Wireless debugging is on."),
    ("pair.cancelled", "Ləğv edildi.", "Отменено.", "Cancelled."),
    ("pair.addr_format",
     "Ünvanı IP:port formatında yazın (məs. 192.168.1.5:37123).",
     "Введите адрес в формате IP:порт (например, 192.168.1.5:37123).",
     "Enter the address as IP:port (e.g. 192.168.1.5:37123)."),
    ("pair.port_number", "Port rəqəm olmalıdır.", "Порт должен быть числом.", "The port must be a number."),
    ("pair.code_digits", "Pairing kodu 6 rəqəmdən ibarət olmalıdır.", "Код сопряжения должен состоять из 6 цифр.", "The pairing code must be 6 digits."),
    // ---- scrcpy
    ("scrcpy.already_open", "Bu cihaz üçün ekran artıq açıqdır.", "Экран этого устройства уже открыт.", "The screen for this device is already open."),
    ("scrcpy.start_failed", "scrcpy başladıla bilmədi: {e}", "Не удалось запустить scrcpy: {e}", "Failed to start scrcpy: {e}"),
    ("scrcpy.not_open", "Bu cihaz üçün açıq ekran yoxdur.", "Для этого устройства нет открытого экрана.", "There is no open screen for this device."),
    ("scrcpy.stop_failed", "scrcpy dayandırıla bilmədi: {e}", "Не удалось остановить scrcpy: {e}", "Failed to stop scrcpy: {e}"),
    ("scrcpy.closed_unexpected", "scrcpy gözlənilmədən bağlandı.", "scrcpy неожиданно закрылся.", "scrcpy closed unexpectedly."),
    ("scrcpy.closed", "scrcpy bağlandı:\n{log}", "scrcpy закрылся:\n{log}", "scrcpy closed:\n{log}"),
    ("dev.gone", "Cihaz artıq qoşulu deyil.", "Устройство больше не подключено.", "The device is no longer connected."),
    ("url.https_only", "Yalnız https linkləri açıla bilər.", "Можно открывать только https-ссылки.", "Only https links can be opened."),
    // ---- asan rejim
    ("easy.cert_failed", "Sertifikat yaradıla bilmədi: {e}", "Не удалось создать сертификат: {e}", "Could not create certificate: {e}"),
    ("easy.no_server", "Server işləmir.", "Сервер не запущен.", "The server is not running."),
    ("easy.tls", "TLS konfiqurasiyası: {e}", "Конфигурация TLS: {e}", "TLS configuration: {e}"),
    ("easy.port", "Port açıla bilmədi: {e}", "Не удалось открыть порт: {e}", "Could not open port: {e}"),
    ("easy.no_network",
     "Kompüter heç bir şəbəkəyə qoşulu deyil. Wi-Fi-a və ya telefonun hotspotuna qoşulun.",
     "Компьютер не подключён ни к одной сети. Подключитесь к Wi-Fi или к точке доступа телефона.",
     "The computer isn't connected to any network. Connect to Wi-Fi or to the phone's hotspot."),
    ("easy.session_ended", "Paylaşım artıq bitib.", "Трансляция уже завершена.", "The session has already ended."),
    ("easy.viewer_title", "Telefon ekranı — EasyConnect", "Экран телефона — EasyConnect", "Phone screen — EasyConnect"),
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_key_is_unique_and_has_all_languages() {
        let mut keys: Vec<&str> = TABLE.iter().map(|r| r.0).collect();
        keys.sort();
        let len = keys.len();
        keys.dedup();
        assert_eq!(len, keys.len(), "təkrarlanan açar var");
        for row in TABLE {
            assert!(!row.1.is_empty() && !row.2.is_empty() && !row.3.is_empty(), "{} boşdur", row.0);
        }
    }

    #[test]
    fn placeholders_and_languages() {
        set("en");
        assert_eq!(tf("pair.found", &[("ip", "1.2.3.4")]), "Phone found (1.2.3.4). Connecting…");
        set("ru");
        assert!(t("err.unknown").starts_with("Произошла"));
        set("az");
        assert_eq!(t("nonexistent.key"), "nonexistent.key");
    }
}
