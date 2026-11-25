"use strict";

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];


const ICONS = {
  usb: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2v14"/><path d="m9 5 3-3 3 3"/><path d="M7 9v3a2 2 0 0 0 2 2h3"/><path d="M17 7v4a2 2 0 0 1-2 2h-3"/><circle cx="12" cy="19" r="3"/><rect x="5.5" y="6" width="3" height="3"/><circle cx="17" cy="6" r="1.5"/></svg>',
  wifi: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M2 8.8a15 15 0 0 1 20 0"/><path d="M5 12.5a10 10 0 0 1 14 0"/><path d="M8.5 16a5 5 0 0 1 7 0"/><circle cx="12" cy="19.5" r="1" fill="currentColor"/></svg>',
  app: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><rect x="6" y="2" width="12" height="20" rx="2"/><path d="M10 18h4"/></svg>',
  emulator: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/></svg>',
};


// ------------------------------------------------------------------ state
const state = {
  devices: [],
  busy: new Set(), // əməliyyat gedən serial-lar
  qrSession: null,
  easy: [], // "Tətbiqlə" qoşulmuş telefonlar
  options: loadOptions(),
};

function loadOptions() {
  const defaults = {
    preset: "standard", screenOff: false, stayAwake: true, alwaysOnTop: false,
    fullscreen: false, noAudio: false, record: false,
  };
  try {
    return { ...defaults, ...JSON.parse(localStorage.getItem("ec.options") || "{}") };
  } catch {
    return defaults;
  }
}

function saveOptions() {
  try { localStorage.setItem("ec.options", JSON.stringify(state.options)); } catch { /* yox */ }
}

// ----------------------------------------------------------------- helpers
function toast(message, kind = "info", ms = 5000) {
  const el = document.createElement("div");
  el.className = `toast ${kind}`;
  el.textContent = message;
  $("#toasts").appendChild(el);
  setTimeout(() => el.remove(), kind === "err" ? Math.max(ms, 9000) : ms);
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

async function call(cmd, args) {
  try {
    return await invoke(cmd, args);
  } catch (e) {
    toast(typeof e === "string" ? e : (e?.message || String(e)), "err");
    throw e;
  }
}

async function withBusy(serial, fn) {
  state.busy.add(serial);
  renderDevices();
  try { return await fn(); } finally { state.busy.delete(serial); renderDevices(); }
}

// ----------------------------------------------------------------- devices
function renderDevices() {
  const list = $("#deviceList");
  const devices = state.devices;
  const total = devices.length + state.easy.length;
  $("#deviceCount").textContent = total ? t("devices.count", { n: total }) : "";

  if (!total) {
    list.innerHTML = `<div class="empty"><b>${esc(t("devices.emptyTitle"))}</b>${esc(t("devices.emptyText"))}</div>`;
    return;
  }

  const easyCards = state.easy.map((e) => {
    const name = e.phone?.name || t("phone");
    const meta = [t("dev.viaApp"), e.phone?.os, e.remote].filter(Boolean).map(esc).join(" · ");
    return `<div class="device" data-easy="${esc(e.id)}">
      <div class="icon">${ICONS.app}</div>
      <div><div class="name">${esc(name)}<span class="badge live">${esc(t("dev.viaAppBadge"))}</span></div><div class="meta">${meta}</div></div>
      <div class="actions">
        <button class="btn primary" data-act="easy-focus">${esc(t("btn.showWindow"))}</button>
        <button class="btn ghost danger" data-act="easy-stop">${esc(t("btn.disconnect"))}</button>
      </div>
    </div>`;
  }).join("");

  list.innerHTML = easyCards + devices.map((d) => {
    const busy = state.busy.has(d.serial);
    const ready = d.state === "device";
    const meta = [
      d.transport === "wifi" ? "Wi-Fi" : d.transport === "usb" ? "USB" : t("dev.emulator"),
      d.androidVersion ? `Android ${esc(d.androidVersion)}` : null,
      esc(d.serial),
    ].filter(Boolean).join(" · ");
    const badge = d.mirroring
      ? `<span class="badge live">${esc(t("dev.mirroring"))}</span>`
      : !ready ? `<span class="badge warn">${esc(d.state)}</span>` : "";
    const hint = !ready ? `<div class="hint">${esc(I18N.az[`state.${d.state}`] ? t(`state.${d.state}`) : t("dev.notReady"))}</div>` : "";

    let actions = "";
    if (ready) {
      actions += d.mirroring
        ? `<button class="btn" data-act="stop" ${busy ? "disabled" : ""}>${esc(t("btn.close"))}</button>`
        : `<button class="btn primary" data-act="start" ${busy ? "disabled" : ""}>${esc(t("btn.open"))}</button>`;
      if (d.transport === "usb") actions += `<button class="btn ghost" data-act="wifi" ${busy ? "disabled" : ""} title="${esc(t("btn.toWifiTitle"))}">${esc(t("btn.toWifi"))}</button>`;
    }
    if (d.transport === "wifi") actions += `<button class="btn ghost danger" data-act="disconnect" ${busy ? "disabled" : ""}>${esc(t("btn.disconnect"))}</button>`;

    return `<div class="device" data-serial="${esc(d.serial)}">
      <div class="icon">${ICONS[d.transport] || ICONS.usb}</div>
      <div><div class="name">${esc(d.displayName)}${badge}</div><div class="meta">${meta}</div></div>
      <div class="actions">${actions}</div>
      ${hint}
    </div>`;
  }).join("");
}

$("#deviceList").addEventListener("click", async (ev) => {
  const btn = ev.target.closest("button[data-act]");
  if (!btn) return;
  const act = btn.dataset.act;
  const easyId = btn.closest(".device").dataset.easy;
  if (easyId) {
    if (act === "easy-focus") invoke("easy_focus", { id: easyId });
    if (act === "easy-stop") invoke("easy_stop", { id: easyId });
    return;
  }
  const serial = btn.closest(".device").dataset.serial;
  try {
    if (act === "start") {
      await withBusy(serial, () => call("start_mirroring", { serial, options: state.options }));
    } else if (act === "stop") {
      await call("stop_mirroring", { serial });
    } else if (act === "wifi") {
      await withBusy(serial, async () => {
        toast(t("toast.switchingWifi"));
        const addr = await call("switch_to_wifi", { serial });
        toast(t("toast.wifiReady", { addr }), "ok");
        loadSaved();
      });
    } else if (act === "disconnect") {
      await withBusy(serial, () => call("disconnect_device", { serial }));
      loadSaved();
    }
  } catch { /* toast artıq göstərilib */ }
  refreshDevices();
});

async function refreshDevices() {
  try {
    state.devices = await invoke("list_devices");
    renderDevices();
  } catch { /* alətlər yoxdursa banner göstərilir */ }
}

// ----------------------------------------------------------------- options
function renderOptions() {
  $$("#presetGroup button").forEach((b) => b.setAttribute("aria-checked", String(b.dataset.preset === state.options.preset)));
  $("#presetHint").textContent = t(`preset.hint.${state.options.preset}`);
  $$(".toggles input[data-opt]").forEach((i) => { i.checked = !!state.options[i.dataset.opt]; });
}

$("#presetGroup").addEventListener("click", (ev) => {
  const b = ev.target.closest("button[data-preset]");
  if (!b) return;
  state.options.preset = b.dataset.preset;
  saveOptions();
  renderOptions();
});

$$(".toggles input[data-opt]").forEach((i) => i.addEventListener("change", () => {
  state.options[i.dataset.opt] = i.checked;
  saveOptions();
}));

// -------------------------------------------------------------------- tabs
let lastDevTab = "qr";
function showTab(name) {
  const isApp = name === "app";
  $$(".mode-switch button").forEach((b) => b.classList.toggle("active", b.dataset.mode === (isApp ? "app" : "dev")));
  $("#devTabs").hidden = isApp;
  if (!isApp) lastDevTab = name;
  $$(".tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
  $$(".tab-body").forEach((b) => { b.hidden = b.dataset.body !== name; });
  if (name === "qr" && !state.qrSession) startQr();
  if (isApp) startAppQr();
  if (name !== "qr" && state.qrSession) {
    invoke("cancel_pairing", { sessionId: state.qrSession });
    state.qrSession = null;
  }
}
$(".mode-switch").addEventListener("click", (ev) => {
  const b = ev.target.closest("button[data-mode]");
  if (b) showTab(b.dataset.mode === "app" ? "app" : lastDevTab);
});
$("#devTabs").addEventListener("click", (ev) => {
  const b = ev.target.closest("button[data-tab]");
  if (b) showTab(b.dataset.tab);
});

// ---------------------------------------------------------------------- QR
function setQrStatus(text, cls = "") {
  const el = $("#qrStatus");
  el.textContent = text;
  el.className = `qr-status ${cls}`;
}

async function startQr() {
  $("#qrImage").classList.remove("done");
  $("#qrImage").innerHTML = `<span class="muted">${esc(t("qr.preparing"))}</span>`;
  setQrStatus("");
  try {
    const qr = await call("start_qr_pairing");
    state.qrSession = qr.sessionId;
    $("#qrImage").innerHTML = qr.qrSvg;
    setQrStatus(t("qr.scanPhone"), "busy");
  } catch {
    $("#qrImage").innerHTML = `<span class="muted">${esc(t("qr.failed"))}</span>`;
  }
}
$("#qrRefresh").addEventListener("click", startQr);

listen("pairing", ({ payload }) => {
  if (payload.sessionId !== state.qrSession) return;
  switch (payload.stage) {
    case "waiting": setQrStatus(payload.message, "busy"); break;
    case "found": setQrStatus(payload.message, "busy"); break;
    case "connected":
      setQrStatus(payload.message, "ok");
      $("#qrImage").classList.add("done");
      state.qrSession = null;
      toast(t("pair.connectedToast", { serial: payload.serial }), "ok");
      refreshDevices();
      loadSaved();
      break;
    case "timeout":
    case "error":
      setQrStatus(payload.message, "err");
      $("#qrImage").classList.add("done");
      state.qrSession = null;
      break;
  }
});

// ------------------------------------------------- app mode (developer mode-suz)
let appQrTimer = null;
async function startAppQr() {
  clearInterval(appQrTimer);
  const img = $("#appQrImage");
  const status = $("#appQrStatus");
  img.classList.remove("done");
  try {
    const qr = await call("easy_new_pairing");
    img.innerHTML = qr.qrSvg;
    let left = qr.expiresIn;
    const hostsKey = qr.hosts.join(",");
    const tabVisible = () => !$('[data-body="app"]').hidden;
    const tick = async () => {
      if (left <= 0) {
        clearInterval(appQrTimer);
        // Bölmə açıqdırsa, köhnəlmiş QR-ı avtomatik yeniləyirik
        if (tabVisible()) { startAppQr(); return; }
        img.classList.add("done");
        status.textContent = t("app.expired");
        status.className = "qr-status err";
        return;
      }
      // Kompüter başqa şəbəkəyə (məs. telefonun hotspotuna) keçibsə, QR-da köhnə IP qalmasın
      if (left % 3 === 0 && tabVisible()) {
        const ips = await invoke("easy_local_ips").catch(() => null);
        if (ips && ips.join(",") !== hostsKey) {
          toast(t("app.networkChanged"));
          startAppQr();
          return;
        }
      }
      status.textContent = t("app.scanStatus", { code: qr.code, time: `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}` });
      status.className = "qr-status busy";
      left -= 1;
    };
    tick();
    appQrTimer = setInterval(tick, 1000);
  } catch {
    img.innerHTML = `<span class="muted">${esc(t("qr.failed"))}</span>`;
  }
}
$("#appQrRefresh").addEventListener("click", startAppQr);

async function refreshEasy() {
  state.easy = await invoke("easy_sessions").catch(() => []);
  renderDevices();
}

listen("easy-session", ({ payload }) => {
  refreshEasy();
  const name = payload.phone?.name || t("phone");
  toast(t("easy.sharing", { name }), "ok");
  // Token birdəfəlikdir — növbəti telefon üçün yenisini hazırla.
  if (!$('[data-body="app"]').hidden) startAppQr();
});

listen("easy-ended", ({ payload }) => {
  state.easy = state.easy.filter((e) => e.id !== payload.id);
  renderDevices();
  const key = `easy.ended.${payload.reason}`;
  toast(I18N.az[key] ? t(key) : t("easy.ended.default"));
});

// ------------------------------------------------------------ manual forms
function bindForm(id, handler) {
  const form = $(id);
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const btn = form.querySelector("button[type=submit]");
    const data = Object.fromEntries(new FormData(form));
    btn.disabled = true;
    const label = btn.textContent;
    btn.textContent = t("btn.connecting");
    try {
      const serial = await handler(data);
      toast(t("toast.connected", { serial }), "ok");
      form.reset();
      refreshDevices();
      loadSaved();
    } catch { /* toast */ } finally {
      btn.disabled = false;
      btn.textContent = label;
    }
  });
}
bindForm("#codeForm", (d) => call("pair_with_code", { host: d.host, code: d.code }));
bindForm("#ipForm", (d) => call("connect_address", { address: d.address }));

// ----------------------------------------------------------- saved devices
async function loadSaved() {
  const list = await invoke("saved_devices").catch(() => []);
  const box = $("#savedList");
  if (!list.length) {
    box.innerHTML = `<p class="muted small">${esc(t("saved.empty"))}</p>`;
    return;
  }
  box.innerHTML = list.map((d) => `
    <div class="saved-row" data-id="${esc(d.id)}">
      <div class="grow"><div><b>${esc(d.name)}</b></div><div class="muted small">${esc(d.ip)}</div></div>
      <label title="${esc(t("saved.autoTitle"))}"><input type="checkbox" data-act="auto" ${d.autoReconnect ? "checked" : ""}/> ${esc(t("saved.auto"))}</label>
      <button class="btn ghost small-btn danger" data-act="forget">${esc(t("saved.forget"))}</button>
    </div>`).join("");
}

$("#savedList").addEventListener("change", (ev) => {
  const i = ev.target.closest("input[data-act=auto]");
  if (!i) return;
  invoke("set_auto_reconnect", { id: i.closest(".saved-row").dataset.id, value: i.checked });
});
$("#savedList").addEventListener("click", async (ev) => {
  const b = ev.target.closest("button[data-act=forget]");
  if (!b) return;
  await invoke("forget_device", { id: b.closest(".saved-row").dataset.id });
  loadSaved();
});

// ------------------------------------------------------------------ events
listen("devices", ({ payload }) => {
  state.devices = payload;
  renderDevices();
});

listen("session", ({ payload }) => {
  if (payload.status === "started" && payload.recordPath) {
    toast(t("rec.started", { path: payload.recordPath }));
  }
  if (payload.status === "exited") {
    if (payload.error) toast(payload.error, "err");
    else if (payload.recordPath) toast(t("rec.saved", { path: payload.recordPath }), "ok");
  }
  refreshDevices();
});

// -------------------------------------------------------------------- init
async function init() {
  renderOptions();
  renderDevices();
  const status = await invoke("tools_status");
  const pill = $("#toolsStatus");
  $("#driverLink").addEventListener("click", (ev) => {
    ev.preventDefault();
    invoke("open_url", { url: status.usbDriverUrl });
  });
  if (!status.ok) {
    pill.removeAttribute("data-i18n");
    pill.textContent = t("tools.notFound");
    pill.className = "pill err";
    const banner = $("#toolsError");
    banner.textContent = status.error;
    banner.hidden = false;
    return;
  }
  pill.removeAttribute("data-i18n");
  pill.textContent = status.adbVersion ? `adb ${status.adbVersion.replace(/^Version\s*/, "")}` : t("tools.ready");
  pill.className = "pill ok";
  pill.title = status.dir || "";
  await refreshDevices();
  refreshEasy();
  loadSaved();
  showTab("app");
}

// ------------------------------------------------------------------ language
function rerenderForLang() {
  applyI18n();
  renderOptions();
  renderDevices();
  loadSaved();
}
$("#langSelect").value = currentLang;
$("#langSelect").addEventListener("change", (ev) => {
  setLang(ev.target.value);
  rerenderForLang();
});
window.addEventListener("ec-lang-changed", () => {
  $("#langSelect").value = currentLang;
  rerenderForLang();
});
applyI18n();
invoke("set_language", { lang: currentLang }).catch(() => {});

init();
