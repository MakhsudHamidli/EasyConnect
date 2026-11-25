# EasyConnect

Android telefonu Windows/macOS kompüterə **bir kliklə** qoşmaq və ekranını göstərmək/idarə etmək üçün masaüstü proqram. Ekran ötürülməsi [scrcpy](https://github.com/Genymobile/scrcpy) ilə işləyir. EasyConnect onun əmr sətrini sadə interfeyslə əvəz edir.

## Mərhələ 1: hazır olanlar

- **QR kodla Wi-Fi qoşulması** (Android 11+): telefonda *Wireless debugging → Pair device with QR code* seçilir, QR skan edilir, qalan addımlar avtomatik icra olunur.
- **6 rəqəmli kodla** və **IP:port ilə** əl ilə qoşulma.
- **USB köməkçisi**: Developer options və USB debugging-i addım-addım açmaq üçün təlimat, Windows USB driver linki.
- **USB-dən Wi-Fi-a keçid** bir düymə ilə (`adb tcpip 5555` + `connect`).
- **Cihaz siyahısı** canlı yenilənir: USB/Wi-Fi, model, Android versiyası, vəziyyət (`unauthorized`, `offline` üçün izah ilə).
- **Ekran rejimləri**: Standart, Oyun, Təqdimat, Zəif Wi-Fi, Kamera. Əlavə seçimlər: telefon ekranını söndür, oyaq saxla, həmişə üstdə, tam ekran, səssiz, videoya yaz (`Videos/EasyConnect`).
- **Yadda saxlanmış cihazlar** və **avtomatik yenidən qoşulma**: Wireless debugging-in dəyişən portu mDNS ilə tapılır.
- **Səhvlərin izahı**: adb/scrcpy səhvləri Azərbaycan dilində izah olunur (Xiaomi icazəsi, səhv kod, fərqli Wi-Fi və s.).

## QR qoşulması necə işləyir

```
EasyConnect                                Telefon
-----------                                -------
1. ad + parol yaradır
   QR = WIFI:T:ADB;S:<ad>;P:<parol>;;  ───► QR skan edilir
                                            2. "<ad>._adb-tls-pairing._tcp" mDNS elanı
3. mDNS ilə tapır (mdns-sd + adb mdns)  ◄───
4. adb pair ip:port parol               ───► pairing
5. "_adb-tls-connect._tcp" tapır
   adb connect ip:port                  ───► qoşuldu
```

Bu Android Studio-nun "Pair devices using Wi-Fi" funksiyası ilə eyni protokoldur.

## Struktur

```
EasyConnect/
├─ src/                    interfeys (HTML/CSS/JS, bundler yoxdur)
├─ src-tauri/
│  ├─ src/
│  │  ├─ lib.rs            Tauri əmrləri, fon izləyiciləri (cihazlar, avtomatik qoşulma)
│  │  ├─ adb.rs            adb əmrləri və çıxışın təhlili
│  │  ├─ pairing.rs        QR/kod/IP ilə qoşulma
│  │  ├─ mdns.rs           Wireless debugging xidmətlərinin axtarışı
│  │  ├─ scrcpy.rs         rejimlər, scrcpy sessiyaları
│  │  ├─ errors.rs         səhv mesajlarının izahı
│  │  ├─ store.rs          yadda saxlanmış cihazlar (devices.json)
│  │  ├─ tools.rs          adb/scrcpy fayllarının yerini tapmaq
│  │  └─ proc.rs           konsolsuz proses + timeout
│  ├─ resources/scrcpy/
│  │  ├─ windows/          scrcpy-win64 faylları (proqrama daxil edilir)
│  │  └─ macos/            scrcpy-macos faylları buraya qoyulmalıdır
│  └─ tauri.*.conf.json    platformaya görə resurslar
└─ app-icon.svg            ikonların mənbəyi (npm run icons)
```

## Lazım olanlar

- [Rust](https://rustup.rs) (stable)
- Node.js (yalnız Tauri CLI üçün, 16+)
- **Windows:** Visual Studio 2022 Build Tools, "Desktop development with C++" (MSVC + Windows SDK), WebView2 (Windows 11-də var)
- **macOS:** Xcode Command Line Tools (`xcode-select --install`)

## İşə salmaq

```bash
npm install
npm run dev        # inkişaf rejimi
npm test           # Rust unit testləri
npm run build      # quraşdırıcı (Windows: MSI + NSIS, macOS: .app + .dmg)
```

### macOS üçün scrcpy faylları

[scrcpy releases](https://github.com/Genymobile/scrcpy/releases) səhifəsindən `scrcpy-macos-aarch64-vX.Y.Z.tar.gz` (Apple Silicon) və ya `x86_64` faylını yükləyin. İçindəki `scrcpy`, `adb`, `scrcpy-server` fayllarını `src-tauri/resources/scrcpy/macos/` qovluğuna köçürün.

Başqa qovluqdan istifadə etmək üçün mühit dəyişəni: `EASYCONNECT_SCRCPY_DIR=/yol/scrcpy`.

## Bilinən məhdudiyyətlər

- QR və kodla qoşulma **Android 11+** tələb edir. Köhnə telefonlarda USB + "Wi-Fi-a keç" istifadə edin.
- mDNS bəzi korporativ/ictimai Wi-Fi şəbəkələrində bloklanır. O halda "Kod ilə" və ya "IP ilə" bölməsindən istifadə edin.
- Windows Firewall ilk açılışda adb/EasyConnect üçün icazə soruşa bilər: **Private networks** icazəsi verin.
- iPhone dəstəyi Mərhələ 3-dədir (yalnız ekranı göstərmək, AirPlay ilə).

## Lisenziyalar

scrcpy və adb: Apache License 2.0 (`src-tauri/resources/scrcpy/windows/LICENSE.txt`).
