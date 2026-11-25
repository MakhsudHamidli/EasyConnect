# EasyConnect Asan rejim protokolu (v1)

## Qoşulma

1. Kompüter `0.0.0.0:47800` portunda (port məşğuldursa, təsadüfi portda) TLS server açır. Self-signed sertifikat ilk açılışda yaradılır və `%APPDATA%\az.easyconnect.app\easy-cert.der` faylında saxlanılır.
2. QR:
   ```
   easyconnect://pair?v=1&n=<kompüter adı>&h=<ip1,ip2>&p=<port>&t=<token>&f=<sha256 hex>
   ```
3. Telefon `h`-dəki ünvanları növbə ilə yoxlayır və TLS qurur. Sertifikatın SHA-256 dəyəri `f` ilə eyni olmalıdır.
4. Telefon `HELLO` göndərir, kompüter `WELCOME` ilə cavab verir. Token yanlışdırsa, `{"ok":false}` göndərilir və əlaqə bağlanır.

## Çərçivə

```
[tip: u8][uzunluq: u32 big-endian][məzmun]
```

| Tip | Ad | İstiqamət | Məzmun |
|---|---|---|---|
| `0x01` | HELLO | telefon → PC | `{"v":1,"token":"…","device":{name,manufacturer,model,os},"capabilities":{"control":false}}` |
| `0x02` | WELCOME | PC → telefon | `{"ok":true,"desktop":"…","control":false}` |
| `0x11` | VIDEO | telefon → PC | `[flags u8][pts u64 BE, µs][H.264 Annex-B]`. flags: `0x01` CONFIG (SPS/PPS), `0x02` KEY |
| `0x12` | REQUEST_KEYFRAME | PC → telefon | boş |
| `0x20` | STATE | telefon → PC | `{"paused":true,"reason":"locked"\|"user"}` |
| `0x10` | VIDEO_META | telefon → PC | `{"w":1088,"h":2400}`. Video ölçüsü (başlanğıcda və telefon döndükdə) |
| `0x21` | PERMS | telefon → PC | `{"control":bool,"keyboard":bool,"controlReady":bool,"blocked":null\|"locked"\|"protected"\|"system"}` |
| `0x30` | CONTROL | PC → telefon | `{"t":"tap","x":0..1,"y":0..1}`, `{"t":"long",…}`, `{"t":"swipe","pts":[[x,y],…],"ms":300}`, `{"t":"scroll","x","y","dy":±1}`, `{"t":"key","k":"back\|home\|recents\|notifications\|backspace\|enter"}`, `{"t":"text","s":"…"}`. Telefon icazəni özü yoxlayır, icazə yoxdursa nəzərə almır. |
| `0x0E` / `0x0F` | PING / PONG | telefon ↔ PC | boş. Telefon hər 5 saniyədən bir PING göndərir. PC 20 saniyə ərzində heç nə almasa, əlaqəni bağlayır. |
| `0x7F` | BYE | hər iki tərəf | `{"reason":"…"}` |

Naməlum tiplər nəzərə alınmır. Telefon PC-dən 64 KB-dan, PC isə telefondan 8 MB-dan böyük mesaj qəbul etmir.

## Video

- H.264, böyük tərəfi ≤ 1600 px, ölçülər 16-ya bölünür, 6 Mbit/s, 30 FPS, hər 2 saniyədən bir açar kadr.
- Ekran dəyişməyəndə son kadr 100 ms-dən bir təkrarlanır.
- PC CONFIG paketini yadda saxlayır və açar kadrların əvvəlinə əlavə edir (WebCodecs `VideoDecoder`, Annex-B).
