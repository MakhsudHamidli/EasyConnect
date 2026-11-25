# EasyConnect: Asan rejimin təhlükəsizlik modeli

Bu sənəd telefon tətbiqi ilə qoşulmanın (kompüterə və ya başqa telefona) hansı təminatları verdiyini və hansıları vermədiyini təsvir edir. Bu qaydaları dəyişən hər kod dəyişikliyi bu sənədi də yeniləməlidir.

## Əsas prinsip

**Telefonun sahibi nəyə icazə verəcəyini özü seçir. Bütün yoxlamalar paylaşan telefonda aparılır.** Qarşı tərəfin nə göndərməsindən asılı olmayaraq, icazə verilməyən heç nə icra olunmur. Bütün qərarlar bir yerdə verilir: [SessionPolicy.kt](../android/app/src/main/java/az/easyconnect/mobile/SessionPolicy.kt).

## İcazələr

| İcazə | Standart | Nəyə imkan verir |
|---|---|---|
| Ekrana baxmaq | Həmişə | Görüntü (qorunan tətbiqlər, kilid ekranı və fasilə istisna) |
| Telefonu idarə etmək | **Söndürülüb** | Toxunma, uzun basma, sürüşdürmə, təkər, Geri/Ana ekran/Son tətbiqlər/Bildirişlər |
| Klaviatura ilə yazmaq | **Söndürülüb** | Fokusdakı mətn sahəsinə yazmaq, silmək, Enter. **Parol sahələri istisnadır.** |
| Səs | **Söndürülüb** | Telefonun *çalınan* səsi (media, oyun, `USAGE_UNKNOWN`). Android 10+. **Mikrofon yazılmır.** Səsin yazılmasını qadağan edən tətbiqlərin səsini sistem vermir. Fasilə, kilid və qorunan tətbiq zamanı səs göndərilmir. |
| Fayllar | **Söndürülüb** | Qarşı tərəfdən gələn fayllar yalnız `Downloads/EasyConnect` qovluğuna yazılır: ad təmizlənir, mövcud fayl üzərinə yazılmır, ölçü həddi 4 GB-dır. Telefondan göndərmək yalnız sistem fayl seçicisində seçilən fayllarla mümkündür. Telefonun fayllarına gizli giriş **yoxdur**. |

Naviqasiya düymələri (Geri, Ana ekran, Son tətbiqlər), Quick Settings, səs səviyyəsi və zəng rejimi heç bir icazəni dəyişmir. Ona görə "idarə" icazəsi verilibsə, bunlar qorunan və sistem ekranlarında da işləyir. Bu, qarşı tərəfə həmin ekrandan çıxmağa imkan verir.

**EasyConnect-in öz ekranları:** əsas ekran uzaqdan idarə oluna bilər. **İcazələr** və **Qorunan tətbiqlər** ekranlarında isə toxunma və yazmaq həmişə bloklanır. Qarşı tərəf özünə icazə verə və ya bank tətbiqini qorumadan çıxara bilmir. Ekran müəyyən edilə bilmirsə, ehtiyat üçün bloklanır.

- Sahib icazələri razılıq ekranında seçir. Paylaşım zamanı da tətbiqdə istənilən an dəyişə bilər.
- İdarə üçün sahib EasyConnect Accessibility xidmətini Ayarlarda **özü** yandırmalıdır. Xidmət yandırılsa belə, aktiv paylaşım və seçilmiş icazə olmadan heç bir əmr icra olunmur.

## Həmişə qüvvədə olan qaydalar

| # | Qayda | Necə |
|---|---|---|
| 1 | İki təsdiq | Tətbiqin razılıq ekranı, sonra Android-in öz "ekranı paylaş" dialoqu. Android 14+ cihazlarda "tək tətbiq" seçimi də var. |
| 2 | Tapjacking-ə qarşı | `filterTouchesWhenObscured`, Android 12+ cihazlarda `setHideOverlayWindows(true)`. Razılıq, icazələr və qorunan tətbiqlər ekranlarında, həmçinin Accessibility dialoqunda. |
| 3 | **Qorunan tətbiqlər** (bank, ödəniş, parol, autentifikator və sahibin seçdikləri) | Belə tətbiq öndə olanda görüntü dayanır və idarə bloklanır. Telefonda "Bu dəfə göstər / Gizli saxla" bildirişi çıxır. İdarə bloklu olduğu üçün qarşı tərəf bu düyməni basa bilmir. "Göstər" seçilsə belə, həmin tətbiqdə idarə **bloklu qalır**. |
| 4 | **Qorunan tətbiqi uzaqdan açmaq olmur** | Qorunan tətbiq son 3 saniyədəki uzaqdan əmrdən sonra açılıbsa, telefon dərhal ana ekrana qayıdır və sahibə xəbər verilir. |
| 5 | **Sistem ekranları** | Ayarlar, icazə dialoqları, tətbiq quraşdırıcısı, Play Store, telefon menecerləri və **EasyConnect-in özü** öndə olanda idarə həmişə bloklanır. Qarşı tərəf özünə icazə verə, nəsə quraşdıra və ya ayarları dəyişə bilmir. |
| 6 | Kilid | Ekran sönəndən kilid açılana qədər nə görüntü, nə də idarə var. |
| 7 | Fasilə / dayandırma | Daimi bildirişdə və tətbiqdə. Fasilə zamanı görüntü və idarə yoxdur. |
| 8 | Şifrələmə | TLS 1.2/1.3. Qarşı tərəfin sertifikatının SHA-256 dəyəri QR-dadır və pin olunur (MITM qeyri-mümkündür). Telefon görüntüləyicisi hər sessiya üçün yeni EC P-256 açarı yaradır. |
| 9 | Yalnız lokal şəbəkə | QR-dakı ünvanlar yalnız şəxsi IPv4 ola bilər. |
| 10 | QR birdəfəlikdir | Token 32 simvoldur, 5 dəqiqə etibarlıdır, sabit vaxtlı müqayisə ilə yoxlanılır. |
| 11 | Məlumat götürülmür | Fayl, kontakt, SMS, mikrofon, `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW` icazələri yoxdur. Accessibility xidməti ekran mətnini oxumur və heç yerə göndərmir. Yalnız ön plandakı tətbiqin paket adına baxır. Clipboard telefondan oxunmur. |
| 12 | İz qalmır | `allowBackup=false`, ehtiyat nüsxə qaydaları hər şeyi istisna edir, ekran yazılmır. |

## Bilinən məhdudiyyətlər

- **Qorunan tətbiqlərin tanınması Accessibility xidməti tələb edir.** Xidmət söndürülübsə (yalnız baxış rejimi), qoruma yalnız Android-in `FLAG_SECURE` bayrağına əsaslanır. Bankların əksəriyyəti bu bayraqdan istifadə edir, amma hamısı yox.
- **Tətbiq dəyişəndə 1–2 kadr gecikmə ola bilər.** Accessibility hadisəsi təxminən 50–100 ms-də gəlir.
- **Qorunan tətbiqlərin avtomatik siyahısı paket adına əsaslanır.** Siyahıda olmayan bank tətbiqini sahib "Qorunan tətbiqlər" ekranından əlavə etməlidir.
- **"Tək tətbiq" paylaşım rejimində toxunuş koordinatları** bütün ekrana görə hesablanır və uyğun gəlməyə bilər.
- **Play Store-dan olmayan tətbiq (APK) üçün** Android 13+ cihazlarda Accessibility-ni yandırmaq üçün əvvəlcə "Məhdud ayarlara icazə ver" lazımdır.
- **Qarşı tərəfin cihazı etibarlı olmalıdır.** Göstərilən görüntünü o cihazdakı proqramlar görə bilər.
