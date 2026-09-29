const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => document.querySelectorAll(selector);
const uiState = {
  page: 1, query: "", searchMode: "song", busy: false,
  hotPage: 1, hotPageSize: 10, hotBusy: false,
  visible: true, connected: false, eqEnabled: false,
  equalizer: null, searchAbort: null, searchKey: ""
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

// 外网扫码时二维码带有访问令牌(?t=xxx), 所有接口请求自动附带;
// 局域网扫码无令牌, 服务器放行。
const ACCESS_TOKEN = new URLSearchParams(window.location.search).get("t") || "";

function withToken(path) {
  if (!ACCESS_TOKEN) return path;
  return path + (path.indexOf("?") >= 0 ? "&" : "?") + "t=" + encodeURIComponent(ACCESS_TOKEN);
}

async function api(path, options = {}) {
  const response = await fetch(withToken(path), {
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
    // 均衡器卡片默认收起: 收起时跳过 DOM 更新(避免每秒轮询重绘), 展开时再渲染一次。
    uiState.equalizer = data.equalizer;
    if (!$("#eqBody")?.classList.contains("hidden")) renderEqualizer(data.equalizer);
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
    container.innerHTML = bands.map((band, index) => `<div class="eq-band"><span>${escapeHtml(band.label)}Hz</span><b><em id="eqValue${index}">${Number(band.level).toFixed(1)}</em>dB</b><input id="eqBand${index}" type="range" min="${eq.minDb}" max="${eq.maxDb}" step="1" value="${band.level}"></div>`).join("");
    bands.forEach((band, index) => {
      const input = $("#eqBand" + index);
      if (!input) return;
      attachThumbOnlyDrag(input);
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

/**
 * 触控优化：滑块必须按住「控件点（滑块）」才能拖动，点轨道空白处不跳变数值。
 *
 * `<input type="range">` 默认点轨道任意位置都会跳值并开始拖动；这里在指针按下时先做
 * 命中判断 —— 命中滑块才放行浏览器原生拖动，否则 preventDefault 拦掉这次手势。
 * 只有粘性标记滑块处于按下状态时才放行，手指移出轨道仍可继续拖动（由原生处理）。
 */
function attachThumbOnlyDrag(input) {
  if (!input || input.dataset.thumbOnly === "1") return;
  input.dataset.thumbOnly = "1";
  const blocked = (event) => {
    const rect = input.getBoundingClientRect();
    const point = event.touches && event.touches.length ? event.touches[0] : event;
    const x = point.clientX;
    const y = point.clientY;
    // 粗略还原轨道几何：控件左右各留半个滑块宽度。
    const thumb = 32;
    const trackLeft = rect.left + thumb / 2;
    const trackRight = rect.right - thumb / 2;
    const span = trackRight - trackLeft;
    if (span <= 0 || y < rect.top - thumb || y > rect.bottom + thumb) return true;
    const min = Number(input.min || 0);
    const max = Number(input.max || 100);
    const ratio = (Number(input.value) - min) / (max - min || 1);
    const thumbX = trackLeft + ratio * span;
    // 命中滑块（含手指触控容差）→ 放行拖动；否则拦掉，避免点轨道跳值。
    return Math.abs(x - thumbX) > Math.max(thumb, 26);
  };
  const guard = (event) => { if (blocked(event)) event.preventDefault(); };
  input.addEventListener("pointerdown", guard, { passive: false });
  input.addEventListener("touchstart", guard, { passive: false });
  input.addEventListener("mousedown", guard);
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
  if (reset) {
    const nextQuery = $("#query").value.trim();
    if (!nextQuery) {
      toast(`请输入${uiState.searchMode === "singer" ? "歌手" : "歌曲"}名称`);
      $("#query").focus();
      return;
    }
    // 同一关键词+同一搜索方式且结果已渲染 → 不重复请求设备(设备端也要查 1GB 曲库)。
    const key = `${uiState.searchMode}|${nextQuery}`;
    if (key === uiState.searchKey && $("#results").childElementCount) {
      $("#searchResultBlock").scrollIntoView({ behavior: "smooth", block: "start" });
      return;
    }
    uiState.page = 1;
    uiState.query = nextQuery;
    uiState.searchKey = key;
    $("#results").innerHTML = "";
    $("#searchResultBlock").classList.remove("hidden");
    $("#resultTitle").textContent = uiState.searchMode === "singer" ? "歌手搜索结果" : "歌曲搜索结果";
    $("#resultSummary").textContent = `正在搜索“${uiState.query}”`;
    $("#more").classList.add("hidden");
    if (uiState.searchAbort) uiState.searchAbort.abort();
    uiState.searchAbort = new AbortController();
  }
  const signal = uiState.searchAbort ? uiState.searchAbort.signal : undefined;
  const requestedPage = uiState.page;
  uiState.busy = true;
  try {
    const data = await api(`/api/v1/songs?q=${encodeURIComponent(uiState.query)}&type=${uiState.searchMode}&page=${requestedPage}&pageSize=20`, { signal });
    const rows = (data.songs || []).map((song, index) => songRow(song, false, false, (requestedPage - 1) * 20 + index)).join("");
    if (rows) $("#results").insertAdjacentHTML("beforeend", rows);
    else if (reset) $("#results").innerHTML = '<div class="empty">没有找到相关歌曲<br><small>请更换关键词后重试</small></div>';
    $("#resultSummary").textContent = `“${uiState.query}”${data.hasMore ? "，还有更多" : ""}`;
    $("#more").classList.toggle("hidden", !data.hasMore);
    uiState.page = requestedPage + 1;
    // 预热下一页: 设备端会把它缓存下来, 用户点「更多」时基本瞬时。
    if (requestedPage === 1 && data.hasMore) {
      window.setTimeout(() => {
        api(`/api/v1/songs?q=${encodeURIComponent(uiState.query)}&type=${uiState.searchMode}&page=2&pageSize=20`)
          .catch(() => {});
      }, 500);
    }
  } catch (error) {
    // 被下一次搜索取消属于正常情况, 不弹提示。
    if (error && error.name !== "AbortError") toast(error.message);
  } finally { uiState.busy = false; }
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
attachThumbOnlyDrag($("#musicVolume"));
$("#musicVolume").addEventListener("input", (event) => { $("#musicValue").textContent = event.target.value; });
$("#musicVolume").addEventListener("change", (event) => updateAudio({ musicVolume: Number(event.target.value) }));
// 均衡器入口(与设备端弹窗等价): 开关 / 恢复标准
$("#eqToggle")?.addEventListener("click", () => updateEqualizer({ enabled: !uiState.eqEnabled }));
$("#eqReset")?.addEventListener("click", () => updateEqualizer({ preset: "标准" }));
// 均衡器文字栏作为折叠按钮: 默认收起; 展开后在同一次页面会话内保持, 重新加载页面回到收起态。
$("#eqCollapse")?.addEventListener("click", () => {
  const body = $("#eqBody");
  if (!body) return;
  const collapsed = body.classList.toggle("hidden");
  $("#eqCollapse").setAttribute("aria-expanded", collapsed ? "false" : "true");
  // 展开时补一次渲染(收起期间轮询跳过了 EQ 的 DOM 更新)。
  if (!collapsed) renderEqualizer(uiState.equalizer);
});
document.addEventListener("visibilitychange", () => { uiState.visible = !document.hidden; if (uiState.visible) refresh(); });
// 搜索进行中暂停状态轮询: 避免与设备端 1GB 曲库查询抢 I/O。
window.setInterval(() => { if (uiState.visible && !uiState.busy) refresh(); }, 1000);
refresh();
loadHot(1);
