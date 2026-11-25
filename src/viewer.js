"use strict";

// Telefon ekranını göstərən və (telefon sahibi icazə veribsə) idarə edən pəncərə.
// Video H.264 (Annex-B) kimi gəlir və WebCodecs ilə açılır.
// Vacib: icazələr TELEFONDA yoxlanılır. Bu fayl yalnız UI-ni uyğunlaşdırır.

const { invoke, Channel } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const { getCurrentWindow, LogicalSize } = window.__TAURI__.window;

const FLAG_CONFIG = 0x01;
const FLAG_KEY = 0x02;

const sessionId = new URLSearchParams(location.search).get("id");
const canvas = document.getElementById("screen");
const ctx = canvas.getContext("2d", { alpha: false });
const bar = document.getElementById("bar");

let decoder = null;
let config = null;
let codec = null;
let needKey = true;
const MAX_LAG_MS = 400;
let latencyBase = null;
let lastKeyRequest = 0;
let lastAspect = 0;
let perms = { control: false, keyboard: false, controlReady: false, blocked: null };
let paused = false;
let phoneName = "";
let adbSerial = null;
let overlayState = { title: "v.waiting", text: "v.starting", vars: {}, spin: true, warn: false };

// ------------------------------------------------------------------ UI
function overlay(titleKey, textKey = "", { spin = false, warn = false, vars = {} } = {}) {
  overlayState = titleKey ? { title: titleKey, text: textKey, vars, spin, warn } : null;
  renderOverlay();
}

function renderOverlay() {
  const el = document.getElementById("overlay");
  if (!overlayState) { el.hidden = true; return; }
  el.hidden = false;
  el.classList.toggle("warn", overlayState.warn);
  document.getElementById("spinner").hidden = !overlayState.spin;
  document.getElementById("ovTitle").textContent = t(overlayState.title, overlayState.vars);
  document.getElementById("ovText").textContent = overlayState.text
    ? (I18N.az[overlayState.text] ? t(overlayState.text, overlayState.vars) : overlayState.text)
    : "";
}

const canControl = () => perms.control && perms.controlReady && !perms.blocked && !paused;
// Geri/Ana ekran/Son tətbiqlər: telefon icazə veribsə, qorunan ekranlarda da işləyir
const canNavigate = () => !!perms.navigate;
const canType = () => perms.keyboard && perms.controlReady && !perms.blocked && !paused;

function renderPerms() {
  const pill = document.getElementById("permPill");
  let key, cls;
  if (perms.blocked) { key = I18N.az[`v.blocked.${perms.blocked}`] ? `v.blocked.${perms.blocked}` : "v.blocked.other"; cls = "warn"; }
  else if ((perms.control || perms.keyboard) && !perms.controlReady) { key = "v.notReady"; cls = "warn"; }
  else if (canControl() && canType()) { key = "v.controlKeyboard"; cls = "ok"; }
  else if (canControl()) { key = "v.controlOnly"; cls = "ok"; }
  else if (canType()) { key = "v.keyboardOnly"; cls = "ok"; }
  else { key = "v.viewOnly"; cls = ""; }
  const text = t(key);
  if (pill.textContent && pill.textContent !== text) flashBar();
  pill.textContent = text;
  pill.className = `pill ${cls}`;
  document.querySelectorAll(".bar button[data-key]").forEach((b) => {
    b.disabled = b.dataset.key === "notifications" ? !canControl() : !canNavigate();
  });
  document.querySelectorAll(".bar button[data-vol]").forEach((b) => { b.disabled = !canNavigate(); });
  document.getElementById("btnRinger").disabled = !canNavigate();
  document.getElementById("btnMute").disabled = !perms.audio;
  document.getElementById("btnSendFile").disabled = !perms.files;
  document.getElementById("btnGetFile").disabled = !perms.files;
  canvas.classList.toggle("control", canControl());
}

// ---- panel: gizli, siçan yuxarı kənara gələndə görünür
let hideTimer = null;
function showBar(ms = 1800) {
  bar.classList.add("show");
  clearTimeout(hideTimer);
  hideTimer = setTimeout(() => { if (!bar.matches(":hover")) bar.classList.remove("show"); }, ms);
}
function flashBar() { showBar(2500); }
document.getElementById("hot").addEventListener("mouseenter", () => showBar());
window.addEventListener("mousemove", (ev) => { if (ev.clientY < 12) showBar(); });
bar.addEventListener("mouseleave", () => showBar(600));

// ----------------------------------------------------------- video
function codecFromConfig(bytes) {
  for (let i = 0; i + 4 < bytes.length; i++) {
    if (bytes[i] !== 0 || bytes[i + 1] !== 0 || bytes[i + 2] !== 1) continue;
    const nal = i + 3;
    if ((bytes[nal] & 0x1f) === 7 && nal + 3 < bytes.length) {
      const hex = (b) => b.toString(16).padStart(2, "0");
      return `avc1.${hex(bytes[nal + 1])}${hex(bytes[nal + 2])}${hex(bytes[nal + 3])}`;
    }
  }
  return "avc1.42e01f";
}

function draw(frame) {
  if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
    canvas.width = frame.displayWidth;
    canvas.height = frame.displayHeight;
    fitWindow(frame.displayWidth, frame.displayHeight);
  }
  ctx.drawImage(frame, 0, 0);
  frame.close();
  if (!paused && overlayState) overlay(null);
}

/** Telefon yan çevriləndə pəncərəni yeni nisbətə uyğunlaşdırır. */
async function fitWindow(w, h) {
  const aspect = w / h;
  if (lastAspect && Math.abs(aspect - lastAspect) < 0.05) return;
  lastAspect = aspect;
  try {
    const maxW = screen.availWidth * 0.9;
    const maxH = screen.availHeight * 0.85;
    let width, height;
    if (aspect < 1) { height = Math.min(maxH, 900); width = height * aspect; }
    else { width = Math.min(maxW, 1280); height = width / aspect; if (height > maxH) { height = maxH; width = height * aspect; } }
    await getCurrentWindow().setSize(new LogicalSize(Math.round(width), Math.round(height)));
  } catch { /* kontent yenə də sığışdırılır */ }
}

function makeDecoder() {
  if (decoder) { try { decoder.close(); } catch { /* */ } }
  decoder = new VideoDecoder({
    output: draw,
    error: (e) => { console.warn("decoder error", e); needKey = true; decoder = null; },
  });
  decoder.configure({ codec, optimizeForLatency: true });
  needKey = true;
}

const KIND_VIDEO = 1;
const KIND_AUDIO = 2;

function onPacket(data) {
  const all = new Uint8Array(data);
  if (all.length < 10) return;
  if (all[0] === KIND_AUDIO) { onAudioPacket(all.subarray(1)); return; }
  if (all[0] !== KIND_VIDEO) return;
  const buf = all.subarray(1);
  const flags = buf[0];
  const pts = Number(new DataView(buf.buffer, buf.byteOffset + 1, 8).getBigUint64(0));
  const payload = buf.subarray(9);

  if (flags & FLAG_CONFIG) {
    config = payload.slice();
    codec = codecFromConfig(config);
    makeDecoder();
    return;
  }
  if (!config) return;
  if (!decoder) makeDecoder();
  const isKey = (flags & FLAG_KEY) !== 0;

  // Gecikmə nəzarəti: kadr real vaxtdan geri qalırsa (şəbəkə/emal növbəsi), yığılmış
  // delta kadrları atıb ən yeni açar kadra keçirik — gecikmə saniyələrə çatmasın.
  const now = performance.now();
  if (isKey && (needKey || latencyBase === null)) latencyBase = now - pts / 1000;
  if (!isKey && !needKey && latencyBase !== null) {
    const lag = now - pts / 1000 - latencyBase;
    const queued = decoder?.decodeQueueSize ?? 0;
    if (lag > MAX_LAG_MS || queued > 4) {
      needKey = true;
      latencyBase = null;
      if (now - lastKeyRequest > 500) {
        lastKeyRequest = now;
        invoke("easy_request_key", { id: sessionId }).catch(() => {});
      }
      return;
    }
    // baza yavaş-yavaş ən yaxşı (ən az gecikmə) halı izləsin
    if (lag < 0) latencyBase += lag;
  }
  if (needKey && !isKey) return;
  needKey = false;

  let chunkData = payload;
  if (isKey) {
    chunkData = new Uint8Array(config.length + payload.length);
    chunkData.set(config, 0);
    chunkData.set(payload, config.length);
  }
  try {
    decoder.decode(new EncodedVideoChunk({ type: isKey ? "key" : "delta", timestamp: pts, data: chunkData }));
  } catch (e) {
    console.warn("decode failed", e);
    needKey = true;
  }
}

// --------------------------------------------------------- control
function send(cmd) {
  invoke("easy_control", { id: sessionId, payload: JSON.stringify(cmd) }).catch(() => {});
}

/** Siçan mövqeyini videonun daxilində 0..1 aralığına çevirir (object-fit: contain nəzərə alınır). */
function toVideo(ev) {
  const r = canvas.getBoundingClientRect();
  if (!canvas.width || !canvas.height) return null;
  const scale = Math.min(r.width / canvas.width, r.height / canvas.height);
  const vw = canvas.width * scale, vh = canvas.height * scale;
  const ox = r.left + (r.width - vw) / 2, oy = r.top + (r.height - vh) / 2;
  const x = (ev.clientX - ox) / vw, y = (ev.clientY - oy) / vh;
  if (x < 0 || x > 1 || y < 0 || y > 1) return null;
  return [Math.round(x * 10000) / 10000, Math.round(y * 10000) / 10000];
}

let stroke = null;
canvas.addEventListener("pointerdown", (ev) => {
  canvas.focus();
  if (ev.button !== 0 || !canControl()) return;
  const p = toVideo(ev);
  if (!p) return;
  canvas.setPointerCapture(ev.pointerId);
  stroke = { start: performance.now(), pts: [p], lastT: performance.now() };
});
canvas.addEventListener("pointermove", (ev) => {
  if (!stroke) return;
  const now = performance.now();
  if (now - stroke.lastT < 16) return;
  const p = toVideo(ev);
  if (!p) return;
  stroke.pts.push(p);
  stroke.lastT = now;
});
canvas.addEventListener("pointerup", (ev) => {
  if (!stroke) return;
  const p = toVideo(ev);
  if (p) stroke.pts.push(p);
  const ms = Math.round(performance.now() - stroke.start);
  const [x0, y0] = stroke.pts[0];
  const last = stroke.pts[stroke.pts.length - 1];
  const moved = Math.hypot(last[0] - x0, last[1] - y0) > 0.015;
  if (!moved) send(ms > 450 ? { t: "long", x: x0, y: y0 } : { t: "tap", x: x0, y: y0 });
  else {
    const step = Math.max(1, Math.ceil(stroke.pts.length / 24));
    const pts = stroke.pts.filter((_, i) => i % step === 0);
    if (pts[pts.length - 1] !== last) pts.push(last);
    send({ t: "swipe", pts, ms: Math.min(Math.max(ms, 80), 3000) });
  }
  stroke = null;
});
canvas.addEventListener("pointercancel", () => { stroke = null; });
canvas.addEventListener("contextmenu", (ev) => {
  ev.preventDefault();
  if (canNavigate()) send({ t: "key", k: "back" });
});
let wheelLock = 0;
canvas.addEventListener("wheel", (ev) => {
  ev.preventDefault();
  if (!canControl()) return;
  const now = performance.now();
  if (now - wheelLock < 120) return;
  wheelLock = now;
  const p = toVideo(ev);
  if (p) send({ t: "scroll", x: p[0], y: p[1], dy: ev.deltaY > 0 ? 1 : -1 });
}, { passive: false });

document.querySelectorAll(".bar button[data-key]").forEach((b) =>
  b.addEventListener("click", () => !b.disabled && send({ t: "key", k: b.dataset.key })));

let textBuf = "";
let textTimer = null;
function flushText() {
  if (textBuf) send({ t: "text", s: textBuf });
  textBuf = "";
  textTimer = null;
}
window.addEventListener("keydown", (ev) => {
  if (ev.ctrlKey && ev.key.toLowerCase() === "v" && canType()) {
    ev.preventDefault();
    navigator.clipboard?.readText().then((s) => { if (s) send({ t: "text", s: s.slice(0, 1000) }); }).catch(() => {});
    return;
  }
  if (ev.ctrlKey || ev.metaKey || ev.altKey) return;
  if (ev.key === "Escape") { if (canNavigate()) send({ t: "key", k: "back" }); return; }
  if (!canType()) return;
  if (ev.key === "Backspace") { flushText(); send({ t: "key", k: "backspace" }); ev.preventDefault(); return; }
  if (ev.key === "Enter") { flushText(); send({ t: "key", k: "enter" }); ev.preventDefault(); return; }
  if (ev.key.length === 1) {
    textBuf += ev.key;
    clearTimeout(textTimer);
    textTimer = setTimeout(flushText, 40);
    ev.preventDefault();
  }
});

// ---------------------------------------------------------- səs (telefonun çalınan səsi)
let audioCtx = null;
let gainNode = null;
let audioDecoder = null;
let audioConfig = null;
let nextAudioTime = 0;
let muted = (() => { try { return localStorage.getItem("ec.muted") === "1"; } catch { return false; } })();

function ensureAudio() {
  if (!audioCtx) {
    audioCtx = new AudioContext({ sampleRate: 48000, latencyHint: "interactive" });
    gainNode = audioCtx.createGain();
    gainNode.gain.value = muted ? 0 : 1;
    gainNode.connect(audioCtx.destination);
  }
  if (audioCtx.state === "suspended") audioCtx.resume().catch(() => {});
}
// Brauzer səsi istifadəçi hərəkətindən sonra açır
["pointerdown", "keydown"].forEach((e) => window.addEventListener(e, () => audioCtx?.state === "suspended" && audioCtx.resume().catch(() => {})));

function renderMute() {
  document.getElementById("icoSound").hidden = muted;
  document.getElementById("icoMuted").hidden = !muted;
  if (gainNode) gainNode.gain.value = muted ? 0 : 1;
}

function playAudio(data) {
  if (!audioCtx) { data.close(); return; }
  const frames = data.numberOfFrames;
  const channels = data.numberOfChannels;
  const buffer = audioCtx.createBuffer(channels, frames, data.sampleRate);
  for (let c = 0; c < channels; c++) {
    const plane = new Float32Array(frames);
    data.copyTo(plane, { planeIndex: c, format: "f32-planar" });
    buffer.copyToChannel(plane, c);
  }
  data.close();
  const now = audioCtx.currentTime;
  // Kiçik bufer (~60 ms); çox gecikibsə və ya qabaqlayıbsa, sıfırlayırıq
  if (nextAudioTime < now + 0.02 || nextAudioTime > now + 0.35) nextAudioTime = now + 0.06;
  const src = audioCtx.createBufferSource();
  src.buffer = buffer;
  src.connect(gainNode);
  src.start(nextAudioTime);
  nextAudioTime += buffer.duration;
}

function onAudioPacket(buf) {
  if (!("AudioDecoder" in window) || buf.length < 9) return;
  const flags = buf[0];
  const pts = Number(new DataView(buf.buffer, buf.byteOffset + 1, 8).getBigUint64(0));
  const payload = buf.subarray(9);
  if (flags & FLAG_CONFIG) {
    audioConfig = payload.slice();
    try { audioDecoder?.close(); } catch { /* */ }
    ensureAudio();
    audioDecoder = new AudioDecoder({ output: playAudio, error: () => { audioDecoder = null; } });
    audioDecoder.configure({ codec: "mp4a.40.2", sampleRate: 48000, numberOfChannels: 2, description: audioConfig });
    return;
  }
  if (!audioDecoder || audioDecoder.state !== "configured") return;
  try {
    audioDecoder.decode(new EncodedAudioChunk({ type: "key", timestamp: pts, data: payload }));
  } catch { /* */ }
}

document.getElementById("btnMute").addEventListener("click", () => {
  muted = !muted;
  try { localStorage.setItem("ec.muted", muted ? "1" : "0"); } catch { /* */ }
  ensureAudio();
  renderMute();
});

// ---------------------------------------------------------- səs səviyyəsi / zəng rejimi
document.querySelectorAll(".bar button[data-vol]").forEach((b) =>
  b.addEventListener("click", () => !b.disabled && send({ t: "vol", d: b.dataset.vol })));
document.getElementById("btnRinger").addEventListener("click", () => {
  if (!canNavigate()) return;
  send({ t: "ringer" });
  vtoast(t("v.ringerChanged"));
});

// ---------------------------------------------------------- fayllar
function vtoast(text, kind = "", ms = 4000, onClick = null) {
  const el = document.createElement("div");
  el.className = `vtoast ${kind}`;
  el.textContent = text;
  if (onClick) {
    const a = document.createElement("a");
    a.textContent = " " + t("v.openFolder");
    a.addEventListener("click", onClick);
    el.appendChild(a);
  }
  document.getElementById("vtoasts").appendChild(el);
  setTimeout(() => el.remove(), ms);
}

const openFolder = () => invoke("easy_open_downloads").catch((e) => vtoast(String(e), "err"));
document.getElementById("btnFolder").addEventListener("click", openFolder);
document.getElementById("btnSendFile").addEventListener("click", () => {
  if (!perms.files) return;
  invoke("easy_send_files", { id: sessionId }).catch((e) => vtoast(String(e), "err"));
});
document.getElementById("btnGetFile").addEventListener("click", () => {
  if (!perms.files) return;
  invoke("easy_request_file", { id: sessionId })
    .then(() => vtoast(t("v.fileRequested")))
    .catch((e) => vtoast(String(e), "err"));
});

// Faylı pəncərəyə sürükləyib atmaq
getCurrentWindow().onDragDropEvent((ev) => {
  const drop = document.getElementById("drop");
  const type = ev.payload.type;
  if (type === "enter" || type === "over") drop.hidden = !perms.files;
  else if (type === "leave") drop.hidden = true;
  else if (type === "drop") {
    drop.hidden = true;
    if (!perms.files) { vtoast(t("v.filesNotAllowed"), "err"); return; }
    invoke("easy_send_paths", { id: sessionId, paths: ev.payload.paths || [] }).catch((e) => vtoast(String(e), "err"));
  }
}).catch(() => {});

const fileReasons = { denied: "v.fileDenied", too_large: "v.fileTooLarge" };
listen("easy-file", ({ payload: f }) => {
  if (f.id !== sessionId) return;
  const mb = (n) => (n / 1048576).toFixed(1);
  if (f.dir === "out") {
    if (!f.done) vtoast(t("v.fileSending", { name: f.name, done: mb(f.bytes), total: mb(f.size) }), "", 1500);
    else if (f.ok) vtoast(t("v.fileSent"), "ok");
    else vtoast(t(fileReasons[f.reason] || "v.fileFailed"), "err", 6000);
  } else {
    if (!f.done && f.bytes === 0) vtoast(t("v.fileReceiving", { name: f.name }), "", 2500);
    else if (f.done && f.ok) vtoast(t("v.fileReceived", { name: f.name }), "ok", 8000, openFolder);
    else if (f.done) vtoast(t("v.fileFailed"), "err", 6000);
  }
});

// ---------------------------------------------- iki rejim: scrcpy (Developer mode)
/** Telefon eyni zamanda adb ilə bu kompüterə qoşuludursa, scrcpy ilə açmağı təklif edirik. */
async function checkAdb() {
  const serial = await invoke("easy_find_adb", { id: sessionId }).catch(() => null);
  if (serial !== adbSerial) {
    adbSerial = serial;
    document.getElementById("btnScrcpy").hidden = !serial;
    if (serial) flashBar();
  }
}
document.getElementById("btnScrcpy").addEventListener("click", async () => {
  if (!adbSerial) return;
  let options = { preset: "standard", stayAwake: true };
  try { options = { ...options, ...JSON.parse(localStorage.getItem("ec.options") || "{}") }; } catch { /* */ }
  try {
    await invoke("start_mirroring", { serial: adbSerial, options });
    await invoke("easy_stop", { id: sessionId });
    getCurrentWindow().close();
  } catch (e) {
    overlay("v.notFound", String(e), { warn: true });
  }
});

// ---------------------------------------------------------- events
function applyState(st) {
  paused = !!st?.paused;
  if (paused) {
    const map = { locked: ["v.locked", "v.lockedText"], user: ["v.userPaused", "v.userPausedText"], protected: ["v.protected", "v.protectedText"] };
    const [ti, tx] = map[st.reason] || ["v.userPaused", ""];
    overlay(ti, tx, { warn: true });
  } else if (st) {
    overlay("v.resuming", "", { spin: true });
    needKey = true;
  }
  renderPerms();
}

listen("easy-state", ({ payload }) => { if (payload.id === sessionId) applyState(payload.state); });
listen("easy-perms", ({ payload }) => {
  if (payload.id !== sessionId) return;
  perms = { ...perms, ...payload.perms };
  renderPerms();
});
listen("easy-ended", ({ payload }) => {
  if (payload.id !== sessionId) return;
  perms = { control: false, keyboard: false, controlReady: false, blocked: null };
  renderPerms();
  overlay("v.ended", "v.endedText");
  try { decoder?.close(); } catch { /* */ }
});
window.addEventListener("ec-lang-changed", () => { renderPerms(); renderOverlay(); updateTitle(); });

function updateTitle() {
  document.title = `${phoneName || t("phone")} — EasyConnect`;
}

async function init() {
  applyI18n();
  renderOverlay();
  renderPerms();
  renderMute();
  showBar(3500); // başlanğıcda paneli qısa müddət göstəririk
  if (!("VideoDecoder" in window)) {
    overlay("v.noDecoder", "v.noDecoderText", { warn: true });
    return;
  }
  const channel = new Channel();
  channel.onmessage = onPacket;
  try {
    const res = await invoke("easy_attach", { id: sessionId, channel });
    phoneName = res.info?.phone?.name || "";
    updateTitle();
    if (res.perms) perms = { ...perms, ...res.perms };
    overlay("v.waiting", "v.waitingText", { spin: true, vars: { name: phoneName || t("phone") } });
    if (res.state) applyState(res.state);
    renderPerms();
    checkAdb();
    setInterval(checkAdb, 3000);
  } catch (e) {
    overlay("v.notFound", String(e), { warn: true });
  }
}

init();
