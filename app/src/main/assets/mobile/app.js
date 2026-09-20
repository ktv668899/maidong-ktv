const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => document.querySelectorAll(selector);
const uiState = {
  page: 1, query: "", searchMode: "singer", busy: false,
  hotPage: 1, hotPageSize: 10, hotBusy: false,
  visible: true, connected: false, eqEnabled: false
};

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"
  }[char]));
}

function setConnected(connected) {
  uiState.connected = connected;
  $("#offline").classList.toggle("hidden", connected);
  $("#connection").innerHTML = connected
    ? "<span></span>已连接麦动 KTV"
    : "<span></span>正在连接麦动 KTV…";
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    cache: "no-store",
    headers: { "Content-Type": "application/json" },
    ...options
  });
  const data = await response.json();
  if (!response.ok || data.ok === false) throw new Error(data.error || "操作失败");
  setConnected(true);
  return data;
}

function toast(text) {
  const element = $("#toast");
  element.textContent = text;
  element.classList.add("show");
  window.setTimeout(() => element.classList.remove("show"), 1600);
}

function songRow(song, queue = false, current = false, index = 0, ranking = false) {
  const number = current ? "♪" : String(index + 1).padStart(2, "0");
  const actions = queue && !current
    ? '<button class="top">置顶</button><button class="delete" aria-label="删除">×</button>'
    : !queue ? '<button class="order">点歌</button>' : "";
  return `<div class="song${ranking && index < 3 ? " podium" : ""}" data-id="${escapeHtml(song.id)}">
    <div class="song-index">${number}</div>
    <div class="info">${current ? '<span class="playing">正在播放</span>' : ""}<b>${escapeHtml(song.title)}</b><p>${escapeHtml(song.singer)}${song.language ? " · " + escapeHtml(song.language) : ""}</p></div>
    ${actions}
  </div>`;
}

function openPage(name) {
  $$("nav button,.page").forEach((element) => element.classList.remove("active"));
  $(`nav button[data-page="${name}"]`)?.classList.add("active");
  $("#" + name)?.classList.add("active");
  if (name === "queue") loadQueue();
  if (name === "search" && !$("#hotList").children.length) loadHot(1);
}

async function refresh() {
  try {
    const data = await api("/api/v1/state");
    const current = data.current || {};
    const count = data.queueSize || 0;
    $("#nowTitle").textContent = current.title || "暂无歌曲";
    $("#nowSinger").textContent = current.singer || "请先点一首喜欢的歌";
    $("#queueBadge b").textContent = count;
    $("#navCount").textContent = count;
    $("#navCount").classList.toggle("hidden", count === 0);
    $("#playButton span:last-child").textContent = data.playing ? "暂停" : "继续";
    $("#playButton").dataset.action = data.playing ? "pause" : "resume";
    $("#playButton .control-icon").textContent = data.playing ? "Ⅱ" : "▶";
    $("#musicVolume").value = data.musicVolume ?? 85;
    $("#micVolume").value = data.micVolume ?? 70;
    $("#musicValue").textContent = data.musicVolume ?? 85;
    // 麦克风音量由功放/混音器硬件调节(滑杆已禁用), 仅保留显示值。
    const micHw = $("#micValue");
    if (micHw) micHw.textContent = data.micVolume ?? 70;
    $("#original").classList.toggle("active", data.vocalMode === "original");
    $("#accompany").classList.toggle("active", data.vocalMode !== "original");
    $("#nextSong").textContent = data.next?.title || "队列为空";
    renderEqualizer(data.equalizer);
  } catch (_) {
    setConnected(false);
  }
}

// ─── 均衡器（与设备端 KtvEqualizer 同步，频段由设备真实能力决定）───
function renderEqualizer(eq) {
  if (!eq) return;
  uiState.eqEnabled = !!eq.enabled;
  const state = $("#eqState");
  if (state) state.textContent = eq.enabled ? (eq.preset || "自定义") : "已关闭";
  const toggle = $("#eqToggle");
  if (toggle) {
    toggle.textContent = eq.enabled ? "关闭均衡器" : "开启均衡器";
    toggle.classList.toggle("active", !!eq.enabled);
  }
  const hint = $("#eqHint");
  if (hint) {
    hint.textContent = eq.globalMix
      ? "当前作用于全局混音（会影响本机所有声音）"
      : "均衡器作用于本机播放输出，不改动系统音量";
  }
  const bands = Array.isArray(eq.bands) ? eq.bands : [];
  const container = $("#eqBands");
  if (container && container.childElementCount !== bands.length) {
    container.innerHTML = bands.map((band, index) => `<label class="eq-band"><span>${escapeHtml(band.label)}Hz</span><b><em id="eqValue${index}">${Number(band.level).toFixed(1)}</em>dB</b><input id="eqBand${index}" type="range" min="${eq.minDb}" max="${eq.maxDb}" step="1" value="${band.level}"></label>`).join("");
    bands.forEach((band, index) => {
      const input = $("#eqBand" + index);
      if (!input) return;
      input.addEventListener("input", () => {
        const value = $("#eqValue" + index);
        if (value) value.textContent = Number(input.value).toFixed(1);
      });
      input.addEventListener("change", () => updateEqualizer({ levels: currentEqLevels() }));
    });
  } else if (container) {
    bands.forEach((band, index) => {
      const input = $("#eqBand" + index);
      // 正在拖动的那根滑杆不要被轮询覆盖。
      if (input && document.activeElement !== input) input.value = band.level;
      const value = $("#eqValue" + index);
      if (value) value.textContent = Number(band.level).toFixed(1);
    });
  }
  const presets = Array.isArray(eq.presets) ? eq.presets : [];
  const presetBox = $("#eqPresets");
  if (presetBox && presetBox.childElementCount !== presets.length) {
    presetBox.innerHTML = presets.map((name) => `<button type="button" class="eq-preset" data-preset="${escapeHtml(name)}">${escapeHtml(name)}</button>`).join("");
    presetBox.querySelectorAll(".eq-preset").forEach((button) => {
      button.addEventListener("click", () => updateEqualizer({ preset: button.dataset.preset }));
    });
  }
  presetBox?.querySelectorAll(".eq-preset").forEach((button) => {
    button.classList.toggle("active", button.dataset.preset === eq.preset);
  });
}

function currentEqLevels() {
  return Array.from($$("#eqBands input[type=range]")).map((input) => Number(input.value));
}

async function updateEqualizer(values) {
  try {
    await api("/api/v1/player/equalizer", { method: "PATCH", body: JSON.stringify(values) });
    await refresh();
  } catch (error) { toast(error.message); }
}

async function playerAction(action) {
  try {
    await api("/api/v1/player/actions", { method: "POST", body: JSON.stringify({ action }) });
    await refresh();
  } catch (error) { toast(error.message); }
}

async function updateAudio(values) {
  try {
    await api("/api/v1/player/audio", { method: "PATCH", body: JSON.stringify(values) });
    await refresh();
  } catch (error) { toast(error.message); }
}

async function search(reset = true) {
  if (uiState.busy) return;
  if (reset) {
    uiState.page = 1;
    uiState.query = $("#query").value.trim();
    if (!uiState.query) {
      toast(`请输入${uiState.searchMode === "singer" ? "歌手" : "歌曲"}名称`);
      $("#query").focus();
      return;
    }
    $("#results").innerHTML = "";
    $("#searchResultBlock").classList.remove("hidden");
    $("#resultTitle").textContent = uiState.searchMode === "singer" ? "歌手搜索结果" : "歌曲搜索结果";
    $("#resultSummary").textContent = `正在搜索“${uiState.query}”`;
  }
  uiState.busy = true;
  try {
    const data = await api(`/api/v1/songs?q=${encodeURIComponent(uiState.query)}&type=${uiState.searchMode}&page=${uiState.page}&pageSize=20`);
    const rows = (data.songs || []).map((song, index) => songRow(song, false, false, (uiState.page - 1) * 20 + index)).join("");
    if (rows) $("#results").insertAdjacentHTML("beforeend", rows);
    else if (reset) $("#results").innerHTML = '<div class="empty">没有找到相关歌曲<br><small>请更换关键词后重试</small></div>';
    $("#resultSummary").textContent = `“${uiState.query}”${data.hasMore ? "，还有更多" : ""}`;
    $("#more").classList.toggle("hidden", !data.hasMore);
    uiState.page += 1;
  } catch (error) { toast(error.message); }
  finally { uiState.busy = false; }
}

async function loadHot(page) {
  if (uiState.hotBusy || page < 1) return;
  uiState.hotBusy = true;
  $("#hotPrev").disabled = true;
  $("#hotNext").disabled = true;
  $("#hotSummary").textContent = "正在加载…";
  try {
    const data = await api(`/api/v1/hot?page=${page}&pageSize=${uiState.hotPageSize}`);
    uiState.hotPage = data.page || page;
    const songs = data.songs || [];
    $("#hotList").innerHTML = songs.map((song, index) =>
      songRow(song, false, false, (uiState.hotPage - 1) * uiState.hotPageSize + index, true)
    ).join("") || '<div class="empty compact">暂无热歌排行</div>';
    $("#hotPage").textContent = `第 ${uiState.hotPage} 页`;
    $("#hotSummary").textContent = songs.length ? `第 ${(uiState.hotPage - 1) * uiState.hotPageSize + 1}-${(uiState.hotPage - 1) * uiState.hotPageSize + songs.length} 名` : "暂无数据";
    $("#hotPrev").disabled = !data.hasPrevious;
    $("#hotNext").disabled = !data.hasMore;
    if (page > 1) $("#hot").scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (error) {
    $("#hotList").innerHTML = '<div class="empty compact">热歌排行加载失败</div>';
    $("#hotSummary").textContent = "请点击翻页重试";
    $("#hotPrev").disabled = uiState.hotPage <= 1;
    $("#hotNext").disabled = false;
    toast(error.message);
  } finally { uiState.hotBusy = false; }
}

async function loadQueue() {
  try {
    const data = await api("/api/v1/queue");
    const queue = data.queue || [];
    $("#queueCount").textContent = `${queue.length} 首`;
    $("#queueList").innerHTML = queue.map((song, index) => songRow(song, true, index === 0, index)).join("") || '<div class="empty">还没有点歌<br><small>去曲库选一首喜欢的歌吧</small></div>';
  } catch (error) { toast(error.message); }
}

document.addEventListener("click", async (event) => {
  const button = event.target.closest("button");
  if (!button) return;
  if (button.dataset.page) openPage(button.dataset.page);
  if (button.dataset.action) playerAction(button.dataset.action);
  if (button.dataset.vocal) updateAudio({ vocalMode: button.dataset.vocal });
  if (button.dataset.searchMode) {
    uiState.searchMode = button.dataset.searchMode;
    $$('[data-search-mode]').forEach((item) => item.classList.toggle("active", item === button));
    $("#query").placeholder = uiState.searchMode === "singer" ? "输入歌手名或拼音" : "输入歌曲名或拼音";
    $("#query").value = "";
    $("#results").innerHTML = "";
    $("#searchResultBlock").classList.add("hidden");
    $("#more").classList.add("hidden");
    $("#query").focus();
  }
  const row = button.closest(".song");
  if (row && button.classList.contains("order")) {
    try {
      await api("/api/v1/queue", { method: "POST", body: JSON.stringify({ songId: row.dataset.id }) });
      button.textContent = "已点"; button.disabled = true; toast("已加入歌单"); refresh();
    } catch (error) { toast(error.message); }
  }
  if (row && button.classList.contains("top")) {
    try {
      await api("/api/v1/queue/" + encodeURIComponent(row.dataset.id), { method: "PATCH", body: "{}" });
      toast("已置顶为下一首"); loadQueue();
    } catch (error) { toast(error.message); }
  }
  if (row && button.classList.contains("delete")) {
    try {
      await api("/api/v1/queue/" + encodeURIComponent(row.dataset.id), { method: "DELETE" });
      toast("已从歌单移除"); loadQueue(); refresh();
    } catch (error) { toast(error.message); }
  }
});

$("#searchForm").addEventListener("submit", (event) => { event.preventDefault(); search(); });
$("#more").addEventListener("click", () => search(false));
$("#hotPrev").addEventListener("click", () => loadHot(uiState.hotPage - 1));
$("#hotNext").addEventListener("click", () => loadHot(uiState.hotPage + 1));
// 音乐音量可调; 麦克风音量由功放硬件控制, 不再向设备发送无效果请求。
$("#musicVolume").addEventListener("input", (event) => { $("#musicValue").textContent = event.target.value; });
$("#musicVolume").addEventListener("change", (event) => updateAudio({ musicVolume: Number(event.target.value) }));
// 均衡器入口(与设备端弹窗等价): 开关 / 恢复标准
$("#eqToggle")?.addEventListener("click", () => updateEqualizer({ enabled: !uiState.eqEnabled }));
$("#eqReset")?.addEventListener("click", () => updateEqualizer({ preset: "标准" }));
document.addEventListener("visibilitychange", () => { uiState.visible = !document.hidden; if (uiState.visible) refresh(); });
window.setInterval(() => { if (uiState.visible) refresh(); }, 1000);
refresh();
loadHot(1);
