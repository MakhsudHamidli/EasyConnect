# EasyConnect buraxılış təlimatı

## Android: imza açarı

| | |
|---|---|
| Açar faylı | `%USERPROFILE%\EasyConnect-signing\easyconnect-upload.jks` (repo-da YOXDUR) |
| Parol və alias | `%USERPROFILE%\EasyConnect-signing\keystore.properties` (repo-da YOXDUR) |
| Alias | `easyconnect-upload` |
| Sertifikat | `CN=EasyConnect, O=EasyConnect`, RSA 4096, 2054-cü ilə qədər |
| SHA-256 | `97:A9:EB:AA:34:AF:50:E0:8A:92:2C:BC:A6:73:C5:32:54:B2:D2:67:F0:FA:E0:1B:17:6E:1F:14:72:1F:68:DD` |

**Bu qovluğun ehtiyat nüsxəsini dərhal çıxarın:** şifrəli USB fləş və ya parol meneceri. Git-ə, WhatsApp-a və ya açıq bulud qovluğuna qoymayın.

Play Console-da **Play App Signing** aktivdir (standart). Bu açar yalnız *upload* açarıdır: tətbiqin əsl imza açarını Google saxlayır. Upload açarı itərsə, Play Console → Setup → App signing → "Request upload key reset" ilə yenisini qeydiyyatdan keçirmək olar. `lite` APK-ları isə birbaşa bu açarla imzalanır: açar itərsə, `lite` istifadəçiləri tətbiqi silib yenidən quraşdırmalı olacaq.

Gradle açarı avtomatik tapır: `android/app/build.gradle.kts` faylı `~/EasyConnect-signing/keystore.properties` faylını oxuyur. Başqa kompüterdə `EASYCONNECT_SIGNING=<properties faylının yolu>` mühit dəyişəni ilə göstərin.

## Android: build

```bash
cd android
./gradlew bundleFullRelease     # Google Play üçün:     app/build/outputs/bundle/fullRelease/app-full-release.aab
./gradlew assembleLiteRelease   # APK ilə paylamaq üçün: app/build/outputs/apk/lite/release/app-lite-release.apk
```

Hər yeni buraxılışdan əvvəl `android/app/build.gradle.kts` faylında `versionCode`-u bir vahid artırın.

## Google Play-də dərc etmək (bir dəfəlik)

1. **Developer hesabı:** https://play.google.com/console. Birdəfəlik ödəniş və şəxsiyyət yoxlaması tələb olunur. Fərdi hesablar üçün Google ilk dərcdən əvvəl qapalı test şərti qoyur (bir neçə test istifadəçisi, müəyyən müddət). Dəqiq tələbləri Console-da yoxlayın.
2. **Create app:** ad EasyConnect, növ App, Free.
3. **App content** bölməsi:
   - **Privacy policy:** URL tələb olunur. Məzmun üçün `docs/SECURITY.md` faylına əsaslanın: tətbiq heç bir məlumat toplamır, ekran yalnız istifadəçinin seçdiyi cihaza şifrəli göndərilir.
   - **Data safety:** "No data collected / No data shared". Ekran görüntüsü cihazdan cihaza ötürülür və serverimizdə saxlanılmır.
   - **Accessibility API declaration:** EasyConnect `isAccessibilityTool=false` olan Accessibility xidməti istifadə edir. Formda izah edin: *"İstifadəçinin özünün başlatdığı və təsdiqlədiyi ekran paylaşımı zamanı, istifadəçinin açıq icazəsi ilə uzaqdan idarə (toxunuş/mətn) üçün. Məlumat toplanmır."* Bir də tətbiqdəki razılıq ekranının videosunu əlavə edin: QR → icazə checkbox-ları → Accessibility dialoqu.
   - **Foreground service (mediaProjection):** istifadəçinin başlatdığı ekran paylaşımı.
   - Content rating anketi, hədəf auditoriya (18+ seçmək ən sadədir).
4. **Release → Production** (və ya əvvəlcə Internal testing): `dist/android/EasyConnect-<versiya>-full.aab` faylını yükləyin.
5. Google-un yoxlaması adətən bir neçə gün çəkir. Accessibility istifadə edən tətbiqlərdə daha uzun ola bilər.

## macOS (masaüstü)

macOS quraşdırıcısını (`.dmg`) yalnız macOS-da yığmaq olar, çünki Apple alətləri Windows-da işləmir. İki yol var.

### A) GitHub Actions ilə (Mac lazım deyil)
1. Layihəni GitHub repozitoriyasına yükləyin (private ola bilər).
2. GitHub → **Actions** → **Build installers** → **Run workflow**.
3. Təxminən 15 dəqiqə sonra həmin run səhifəsinin **Artifacts** bölməsində bunlar olacaq:
   - `EasyConnect-macos-aarch64`: Apple Silicon (M1/M2/M3/M4) üçün `.dmg`
   - `EasyConnect-macos-x86_64`: Intel Mac üçün `.dmg`
   - `EasyConnect-windows-x64`: Windows `setup.exe` və `.msi`

Workflow: [.github/workflows/build.yml](../.github/workflows/build.yml). Private repo üçün macOS dəqiqələri pulsuz limitdən daha sürətli sərf olunur.

### B) Mac kompüterdə
```bash
xcode-select --install                      # bir dəfə
curl https://sh.rustup.rs -sSf | sh         # Rust, bir dəfə
npm ci
bash scripts/fetch-scrcpy-macos.sh          # bu Mac-in arxitekturası üçün scrcpy+adb
npx tauri build                             # → src-tauri/target/release/bundle/dmg/EasyConnect_<versiya>_<arch>.dmg
```

### İstifadəçinin ilk açılışı (Gatekeeper)
Tətbiq ad-hoc imzalanıb, Apple tərəfindən notarize olunmayıb. İnternetdən yüklənəndə macOS onu ilk dəfə açmağa icazə vermir:
1. DMG-ni açıb **EasyConnect**-i **Applications** qovluğuna sürükləyin.
2. Tətbiqi açın. "Apple cannot verify…" mesajı çıxanda **Done** basın.
3. **System Settings → Privacy & Security** bölməsində, aşağıda "EasyConnect was blocked…" yanında **Open Anyway** basın və təsdiqləyin.
4. İlk qoşulmada "EasyConnect would like to find devices on your local network" sualına **Allow** cavabı verin.

Bu addımları aradan qaldırmaq üçün **Apple Developer Program** (illik ödəniş) lazımdır: Developer ID sertifikatı ilə imza və notarization. Bundan sonra Tauri bunları build zamanı avtomatik edir: `APPLE_CERTIFICATE`, `APPLE_CERTIFICATE_PASSWORD`, `APPLE_SIGNING_IDENTITY`, `APPLE_ID`, `APPLE_PASSWORD`, `APPLE_TEAM_ID` mühit dəyişənlərini təyin etmək kifayətdir (Tauri sənədləri: "macOS Code Signing").

## Windows (masaüstü)

```bash
npm run build
```

Nəticə:
- `src-tauri/target/release/bundle/nsis/EasyConnect_<versiya>_x64-setup.exe`: istifadəçilər üçün tövsiyə olunan quraşdırıcı.
- `src-tauri/target/release/bundle/msi/EasyConnect_<versiya>_x64_en-US.msi`: korporativ paylama üçün.

Quraşdırıcı rəqəmsal imzalı deyil, ona görə Windows SmartScreen "Windows protected your PC" pəncərəsi göstərəcək. İstifadəçi **More info → Run anyway** basmalıdır. Bu xəbərdarlığı aradan qaldırmaq üçün kod imzalama sertifikatı lazımdır (OV/EV sertifikatı və ya Azure Trusted Signing). Pulludur və şirkət/şəxs yoxlaması tələb edir.
