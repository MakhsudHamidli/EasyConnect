//! EasyConnect "Asan rejim" protokolu (versiya 1).
//!
//! Hər mesaj: `[tip: u8][uzunluq: u32 BE][məzmun]`. Bütün əlaqə TLS daxilindədir.
//! Ətraflı təsvir: `docs/PROTOCOL.md`. Android tərəfi: `android/.../Protocol.kt`.

use std::io::{self, Read};

pub const MAX_FRAME: usize = 8 * 1024 * 1024;

pub mod msg {
    /// telefon → PC: JSON {v, token, device:{...}, capabilities:{...}}
    pub const HELLO: u8 = 0x01;
    /// PC → telefon: JSON {ok, desktop, reason?}
    pub const WELCOME: u8 = 0x02;
    /// telefon → PC: [flags u8][pts u64 BE][H.264 Annex-B]
    pub const VIDEO: u8 = 0x11;
    /// PC → telefon: boş — yeni açar kadr (keyframe) istə
    pub const REQUEST_KEYFRAME: u8 = 0x12;
    /// telefon → PC: JSON {paused, reason}
    pub const STATE: u8 = 0x20;
    /// telefon → PC: JSON {control, keyboard, controlReady, blocked}
    /// Telefonun sahibinin verdiyi icazələr. Yoxlama TELEFONDA aparılır — PC yalnız göstərir.
    pub const PERMS: u8 = 0x21;
    /// PC → telefon: JSON idarə əmri (tap/swipe/long/scroll/key/text)
    pub const CONTROL: u8 = 0x30;
    /// telefon → PC: JSON {w, h} — video ölçüsü (telefon-telefon görüntüləyicisi üçün)
    pub const VIDEO_META: u8 = 0x10;
    /// telefon → PC: [flags u8][pts u64 BE][AAC] — telefonun çalınan səsi
    pub const AUDIO: u8 = 0x13;
    /// hər iki istiqamət: JSON {id, name, size}
    pub const FILE_BEGIN: u8 = 0x40;
    /// hər iki istiqamət: [id u32 BE][bayt...]
    pub const FILE_DATA: u8 = 0x41;
    /// hər iki istiqamət: JSON {id, ok, reason?}
    pub const FILE_END: u8 = 0x42;
    /// PC → telefon: telefonda fayl seçicisini aç
    pub const FILE_REQUEST: u8 = 0x43;
    pub const PING: u8 = 0x0E;
    pub const PONG: u8 = 0x0F;
    /// hər iki tərəf: JSON {reason}
    pub const BYE: u8 = 0x7F;
}

pub mod video_flags {
    pub const CONFIG: u8 = 0x01;
    pub const KEY: u8 = 0x02;
}

pub fn encode(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(5 + payload.len());
    out.push(kind);
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(payload);
    out
}

/// Qismən gələn məlumatı yığıb tam mesajları ayıran bufer.
/// Oxuma timeout ilə kəsiləndə vəziyyət itmir.
#[derive(Default)]
pub struct FrameReader {
    buf: Vec<u8>,
}

impl FrameReader {
    /// Mövcud baytları oxuyur. `Ok(false)` — əlaqə bağlandı.
    pub fn fill<R: Read>(&mut self, r: &mut R) -> io::Result<bool> {
        let mut chunk = [0u8; 64 * 1024];
        match r.read(&mut chunk) {
            Ok(0) => Ok(false),
            Ok(n) => {
                self.buf.extend_from_slice(&chunk[..n]);
                Ok(true)
            }
            Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut | io::ErrorKind::Interrupted) => Ok(true),
            Err(e) => Err(e),
        }
    }

    #[cfg(test)]
    pub fn push(&mut self, bytes: &[u8]) {
        self.buf.extend_from_slice(bytes);
    }

    /// Tam mesaj varsa qaytarır.
    pub fn next(&mut self) -> io::Result<Option<(u8, Vec<u8>)>> {
        if self.buf.len() < 5 {
            return Ok(None);
        }
        let len = u32::from_be_bytes([self.buf[1], self.buf[2], self.buf[3], self.buf[4]]) as usize;
        if len > MAX_FRAME {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "mesaj çox böyükdür"));
        }
        if self.buf.len() < 5 + len {
            return Ok(None);
        }
        let kind = self.buf[0];
        let payload = self.buf[5..5 + len].to_vec();
        self.buf.drain(..5 + len);
        Ok(Some((kind, payload)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_split_frames() {
        let mut wire = encode(msg::HELLO, b"{\"a\":1}");
        wire.extend(encode(msg::VIDEO, &[1, 2, 3]));
        let mut r = FrameReader::default();
        r.push(&wire[..3]);
        assert!(r.next().unwrap().is_none());
        r.push(&wire[3..]);
        assert_eq!(r.next().unwrap(), Some((msg::HELLO, b"{\"a\":1}".to_vec())));
        assert_eq!(r.next().unwrap(), Some((msg::VIDEO, vec![1, 2, 3])));
        assert!(r.next().unwrap().is_none());
    }

    #[test]
    fn rejects_huge_frame() {
        let mut r = FrameReader::default();
        r.push(&[msg::VIDEO, 0xFF, 0xFF, 0xFF, 0xFF]);
        assert!(r.next().is_err());
    }
}
