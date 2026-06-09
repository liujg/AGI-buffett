"use strict";

const COLORS = { assets: "#22d3ee", revenue: "#34d399", profit: "#fbbf24", price: "#22d3ee" };
const CUR_WORD = { CNY: "元", HKD: "港元", USD: "美元" };
const curWord = (c) => CUR_WORD[c] || "元";
let WATCHLIST = null;
let activeGroup = null;
let activeSymbol = null;
let CUR = null;

// ---------- helpers ----------
const $ = (sel) => document.querySelector(sel);
function h(html) { const t = document.createElement("template"); t.innerHTML = html.trim(); return t.content.firstChild; }
function fmtYi(v) {
  if (v === null || v === undefined) return null;
  const n = Number(v);
  return n.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
function api(path) { return fetch(path).then(r => r.json()); }
function postApi(path, body) {
  return fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body || {}),
  }).then(r => r.json());
}

// ---------- clock ----------
function tick() {
  const d = new Date();
  const p = (x) => String(x).padStart(2, "0");
  $("#clock").textContent = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
setInterval(tick, 1000); tick();

// ---------- watchlist ----------
async function loadWatchlist() {
  WATCHLIST = await api("/api/watchlist");
  bindAddForm();
  renderWatchlist();
}

// 渲染分组标签 + 当前分组列表(增删后复用)
function renderWatchlist() {
  const groups = WATCHLIST.groups || [];
  const upd = WATCHLIST.updatedAt || WATCHLIST.updated_at;
  $("#wl-updated").textContent = upd ? `updated ${upd}` : "";
  const tabs = $("#group-tabs"); tabs.innerHTML = "";
  groups.forEach(g => {
    const t = h(`<div class="tab" data-key="${g.key}">${g.name}<span class="count">${(g.items || []).length}</span></div>`);
    t.onclick = () => selectGroup(g.key);
    tabs.appendChild(t);
  });
  // 添加表单里的分组下拉
  const gsel = $("#wl-group");
  if (gsel) gsel.innerHTML = groups.map(g => `<option value="${g.key}">${g.name}</option>`).join("");
  if (groups.length && !groups.find(g => g.key === activeGroup)) activeGroup = groups[0].key;
  selectGroup(activeGroup || (groups[0] && groups[0].key));
}

function selectGroup(key) {
  if (!key) return;
  activeGroup = key;
  document.querySelectorAll(".tab").forEach(t => t.classList.toggle("active", t.dataset.key === key));
  const gsel = $("#wl-group"); if (gsel) gsel.value = key;
  const group = (WATCHLIST.groups || []).find(g => g.key === key);
  const items = (group && group.items) || [];
  const list = $("#stock-list"); list.innerHTML = "";
  items.forEach(it => {
    const item = h(`<div class="stock-item" data-sym="${it.symbol}">
        <div class="nm"><b>${it.name || it.symbol}</b><span>${it.symbol}</span></div>
        <span class="badge ${it.market}">${it.market}</span>
        <button class="del" title="从该分组删除">✕</button>
      </div>`);
    item.onclick = () => { activeSymbol = it.symbol; markActive(); loadStock(it.symbol, it.market); };
    item.querySelector(".del").onclick = (e) => { e.stopPropagation(); removeWatch(it); };
    list.appendChild(item);
  });
  // 默认展示该分组第一个标的(当前选中项不在本组时,含首次进入/切换分组)
  if (items.length && !items.some(it => it.symbol === activeSymbol)) {
    activeSymbol = items[0].symbol;
    loadStock(items[0].symbol, items[0].market);
  }
  markActive();
}
function markActive() {
  document.querySelectorAll(".stock-item").forEach(s => s.classList.toggle("active", s.dataset.sym === activeSymbol));
}

// 添加表单(只绑定一次)
let addFormBound = false;
function bindAddForm() {
  if (addFormBound) return;
  addFormBound = true;
  const btn = $("#wl-add-btn"), form = $("#wl-add-form"), msg = $("#wl-add-msg");
  if (!btn || !form) return;
  btn.onclick = () => {
    form.hidden = !form.hidden;
    btn.classList.toggle("open", !form.hidden);
    if (!form.hidden) { msg.textContent = ""; if (activeGroup) $("#wl-group").value = activeGroup; $("#wl-sym").focus(); }
  };
  form.onsubmit = async (e) => {
    e.preventDefault();
    const symbol = $("#wl-sym").value.trim();
    if (!symbol) { msg.textContent = "请输入代码"; msg.className = "wl-msg err"; return; }
    const payload = { symbol, market: $("#wl-mkt").value, name: $("#wl-name").value.trim(), group: $("#wl-group").value };
    msg.textContent = "添加中…"; msg.className = "wl-msg";
    const res = await postApi("/api/watchlist/add", payload).catch(err => ({ error: String(err) }));
    if (res && res.error) { msg.textContent = "失败:" + res.error; msg.className = "wl-msg err"; return; }
    WATCHLIST = res; activeGroup = payload.group;
    renderWatchlist();
    $("#wl-sym").value = ""; $("#wl-name").value = "";
    msg.textContent = `已添加 ${symbol}(行情/报表需运行 fetch 抓取)`; msg.className = "wl-msg ok";
  };
}

async function removeWatch(it) {
  const gname = ((WATCHLIST.groups || []).find(g => g.key === activeGroup) || {}).name || activeGroup;
  if (!confirm(`从「${gname}」移除 ${it.name || it.symbol}(${it.symbol})?`)) return;
  const res = await postApi("/api/watchlist/remove", { symbol: it.symbol, group: activeGroup })
    .catch(err => ({ error: String(err) }));
  if (res && res.error) { alert("删除失败:" + res.error); return; }
  WATCHLIST = res;
  if (activeSymbol === it.symbol) {
    activeSymbol = null;
    $("#detail").innerHTML = `<div class="empty mono">◂ 选择左侧标的查看基本面</div>`;
  }
  renderWatchlist();
}

// ---------- stock detail ----------
async function loadStock(symbol, market) {
  const d = $("#detail");
  d.innerHTML = `<div class="empty mono">加载 ${symbol} …</div>`;
  const data = await api(`/api/stock?symbol=${encodeURIComponent(symbol)}&market=${market}`);
  renderDetail(data);
}

function kpi(label, v, unit, cls) {
  const txt = v === null ? `<span class="value na">—</span>`
    : `<span class="value">${v}${unit ? `<small>${unit}</small>` : ""}</span>`;
  return `<div class="kpi ${cls || ""}"><div class="label">${label}</div>${txt}</div>`;
}

function renderDetail(s) {
  const d = $("#detail");
  const badge = `<span class="badge ${s.market}">${s.market}</span>`;
  const head = `<div class="detail-head">
      <h1>${s.name || s.symbol}</h1>
      <span class="code">${s.symbol}</span> ${badge}
      <div class="rpt">${s.hasData ? `报告期 ${s.reportDate || "—"}<br/>年报 ${s.annualDate || "—"}` : ""}</div>
    </div>`;

  if (!s.hasData) {
    d.innerHTML = head + `<div class="notice">暂无基本面数据<br/><br/>
      ${s.market === "A" ? "请先运行:agibuffett fetch " + s.symbol : s.market + " 市场抓取待支持(目前仅 A 股)"}</div>`;
    return;
  }

  const k = s.kpi || {};
  const ratio = k.assetLiabilityRatio;
  const dy = k.dividendYield;
  // 股息率口径:去年(最近完整年度)每股分红合计 ÷ 当前股价
  const dyLabel = k.dividendYieldYear
    ? `股息率 <small style="color:var(--txt-dim);font-weight:400">${k.dividendYieldYear}股息/现价</small>`
    : "股息率";
  const yi = "亿" + curWord(s.currency);  // 亿元 / 亿港元 / 亿美元
  const kpis = `<div class="kpis">
    ${kpi("总资产", fmtYi(k.totalAssets), yi)}
    ${kpi("总负债", fmtYi(k.totalLiabilities), yi)}
    ${kpi("净资产", fmtYi(k.totalEquity), yi, "good")}
    ${kpi("货币资金", fmtYi(k.monetaryFunds), yi, "good")}
    ${kpi("资产负债率", ratio === null || ratio === undefined ? null : ratio, "%", ratio != null && ratio < 40 ? "good" : "warn")}
    ${kpi("营收(年)", fmtYi(k.revenue), yi)}
    ${kpi("归母净利(年)", fmtYi(k.netProfit), yi, "good")}
    ${kpi(dyLabel, dy === null || dy === undefined ? null : dy, "%", dy != null && dy >= 2 ? "good" : "")}
  </div>`;

  const t = s.trend || {};
  const trendSvg = trendChart(t);

  const segBtn = (adj, label) => {
    const has = s.price && Array.isArray(s.price[adj]);
    return `<button class="seg-btn ${has ? "" : "disabled"}" data-adj="${adj}" ${has ? `onclick="setAdjust('${adj}')"` : ""}>${label}</button>`;
  };
  const priceCard = s.price
    ? `<div class="card">
         <h3>近期股价 · 收盘
           <span class="seg" style="margin-left:auto">
             ${segBtn("raw", "不复权")}${segBtn("qfq", "前复权")}${segBtn("hfq", "后复权")}
           </span>
         </h3>
         <div id="price-svg" class="kline"></div>
         <div class="legend" id="price-meta"></div>
       </div>`
    : `<div class="card"><h3>近期股价</h3><div class="notice">无行情数据<br/>agibuffett fetch ${s.symbol} --only market</div></div>`;

  const dividends = s.dividends || [];
  const cashCell = (x) => {
    const times = x.times > 1 ? ` <small style="color:var(--txt-dim)">·${x.times}笔</small>` : "";
    if (x.cashPer10 != null) return `${x.cashPer10} <small>元/10股</small>${times}`;
    if (x.cashPerShare != null) return `${x.cashPerShare} <small>${curWord(x.currency || s.currency)}/股</small>${times}`;
    return "—";
  };
  // 股本减少率:回购缩股为正(绿色);股本增加(增发/转增)不计入资本回报,灰字标注
  const reductionCell = (x) => {
    if (x.shareNote) return `<small style="color:var(--txt-dim)">${x.shareNote}</small>`;
    const v = x.shareReduction;
    if (v == null) return `—`;
    return v > 0 ? `<span style="color:var(--green)">+${v}%</span>` : `0%`;
  };
  // 同比增长:正绿负红
  const growthCell = (v) => v == null
    ? `—`
    : `<span style="color:${v >= 0 ? "var(--green)" : "var(--red)"}">${v >= 0 ? "+" : ""}${v}%</span>`;
  const divCard = dividends.length
    ? `<div class="card"><h3>年度经营与回报 · 资本回报率 = 年末股息率 + 股本减少率(股本减少率 = 较上年末总股本的减少幅度)</h3>
        <div class="divtab-wrap"><table class="divtab"><thead><tr>
          <th>年度</th><th>营收增长</th><th>利润增长</th><th>现金分红(全年)</th><th>年末收盘</th><th>年末股息率</th><th>股本减少率</th><th>资本回报率</th><th>最晚除息日</th></tr></thead>
        <tbody>${dividends.map(x => `<tr>
            <td>${x.reportDate || "—"}</td>
            <td class="num">${growthCell(x.revenueGrowth)}</td>
            <td class="num">${growthCell(x.profitGrowth)}</td>
            <td class="num">${cashCell(x)}</td>
            <td class="num">${x.yearEndClose != null ? x.yearEndClose : "—"}</td>
            <td class="num" style="color:var(--cyan)">${x.yieldYearEnd != null ? x.yieldYearEnd + "%" : "—"}</td>
            <td class="num">${reductionCell(x)}</td>
            <td class="num" style="color:var(--amber);font-weight:600">${x.capitalReturn != null ? x.capitalReturn + "%" : "—"}</td>
            <td>${x.exDate || "—"}</td></tr>`).join("")}</tbody></table></div></div>`
    : "";

  CUR = s;
  // 默认展示不复权(真实历史价);没有则退到前复权/后复权
  ADJ = s.price ? (["raw", "qfq", "hfq"].find(a => Array.isArray(s.price[a])) || "raw") : "raw";
  d.innerHTML = head + kpis + `<div class="charts">
      <div class="card"><h3>规模与盈利趋势 · 年报(亿元,标注净利同比)</h3>
        <div class="chart-scroll" id="trend-scroll">${trendSvg}</div>
        <div class="legend">
          <span><i style="background:${COLORS.assets}"></i>总资产</span>
          <span><i style="background:${COLORS.revenue}"></i>营收</span>
          <span><i style="background:${COLORS.profit}"></i>归母净利</span>
          <span style="margin-left:auto">▸ 可左右滑动看全部年份</span>
        </div></div>
      ${priceCard}
    </div>` + divCard;
  if (s.price) renderPrice();
  const ts = $("#trend-scroll"); if (ts) ts.scrollLeft = ts.scrollWidth;  // 默认看最新
}

// 复权方式切换:仅重绘价格图,不重新请求
const ADJ_NAME = { raw: "不复权", qfq: "前复权", hfq: "后复权" };
let ADJ = "raw";
function setAdjust(adj) {
  if (!CUR || !CUR.price || !Array.isArray(CUR.price[adj])) return;
  ADJ = adj;
  document.querySelectorAll(".seg-btn").forEach(b => b.classList.toggle("active", b.dataset.adj === adj));
  renderPrice();
}
// 月K线视图状态(雪球式:固定窗口 + 拖动平移)
let KLINE = null;
const KWIN_DEFAULT = 54;  // 默认窗口约 4.5 年(月K)
const KWIN_MIN = 12, KWIN_MAX = 240;

function renderPrice() {
  const p = CUR.price;
  document.querySelectorAll(".seg-btn").forEach(b => b.classList.toggle("active", b.dataset.adj === ADJ));
  const host = $("#price-svg");
  if (!host) return;
  const months = p.months || [];
  const ohlc = p[ADJ + "Ohlc"] || [];
  // 收集有效月K点(回退到收盘价构造平价蜡烛,保证仍可绘制)
  const closes = p[ADJ] || [];
  const data = [];
  months.forEach((m, i) => {
    const c = ohlc[i];
    if (c && c.every(x => x != null && !isNaN(x))) {
      data.push({ m, o: +c[0], h: +c[1], l: +c[2], c: +c[3] });
    } else if (closes[i] != null && !isNaN(closes[i])) {
      const v = +closes[i];
      data.push({ m, o: v, h: v, l: v, c: v });
    }
  });
  if (data.length < 2) {
    host.innerHTML = `<svg viewBox="0 0 560 260" style="width:100%"></svg>`;
    KLINE = null;
  } else {
    const count = Math.min(data.length, KWIN_DEFAULT);
    KLINE = { host, data, count, start: data.length - count, hover: -1, drag: null };
    bindKline(host);
    drawKline();
  }
  const last = { raw: p.lastRaw, qfq: p.lastQfq, hfq: p.lastHfq }[ADJ];
  const meta = $("#price-meta");
  if (meta) meta.innerHTML = `<span>最新(${ADJ_NAME[ADJ]}) <b style="color:var(--cyan)">${fmtYi(last)}</b> ${curWord(CUR.currency)}</span>
      <span>${months[0]} → ${months[months.length - 1]} · 月K</span>
      <span style="margin-left:auto">▸ 按住拖动 / 滚轮 左右查看</span>`;
}

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

// 当前窗口下每根蜡烛的槽宽(与 drawKline 的 pad 保持一致)
function klineCandleW() {
  const innerW = (KLINE.host.clientWidth || 560) - 8 - 52;
  return innerW / KLINE.count;
}
function klinePan(start, shift) {
  KLINE.start = clamp(start + shift, 0, KLINE.data.length - KLINE.count);
}

// 全局指针/窗口级监听只绑定一次,始终作用于当前的 KLINE.host
let KLINE_GLOBAL_BOUND = false;
function bindKlineGlobal() {
  if (KLINE_GLOBAL_BOUND) return;
  KLINE_GLOBAL_BOUND = true;
  window.addEventListener("mousemove", (e) => {
    if (!KLINE) return;
    if (KLINE.drag) {
      klinePan(KLINE.drag.start, Math.round((KLINE.drag.x - e.clientX) / klineCandleW()));
      drawKline();
    } else if (KLINE.host.contains(e.target)) {
      const rect = KLINE.host.getBoundingClientRect();
      const idx = Math.floor((e.clientX - rect.left - 8) / klineCandleW());
      const h = (idx >= 0 && idx < KLINE.count) ? KLINE.start + idx : -1;
      if (h !== KLINE.hover) { KLINE.hover = h; drawKline(); }
    }
  });
  window.addEventListener("mouseup", () => {
    if (KLINE && KLINE.drag) { KLINE.drag = null; KLINE.host.classList.remove("dragging"); }
  });
  window.addEventListener("resize", () => { if (KLINE) drawKline(); });
}

// 容器级监听:每个新建的容器绑定一次
function bindKline(host) {
  bindKlineGlobal();
  if (host._klineBound) return;
  host._klineBound = true;

  host.addEventListener("mousedown", (e) => {
    if (!KLINE) return;
    KLINE.drag = { x: e.clientX, start: KLINE.start };
    host.classList.add("dragging");
  });
  host.addEventListener("mouseleave", () => {
    if (KLINE && KLINE.hover !== -1) { KLINE.hover = -1; drawKline(); }
  });

  // 滚轮:左右平移(并阻止整页滚动)
  host.addEventListener("wheel", (e) => {
    if (!KLINE) return;
    const d = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
    if (d === 0) return;
    e.preventDefault();
    klinePan(KLINE.start, d > 0 ? 1 : -1);
    drawKline();
  }, { passive: false });

  // 触摸:横向滑动平移(纵向交给页面滚动)
  host.addEventListener("touchstart", (e) => {
    if (!KLINE || !e.touches[0]) return;
    KLINE.drag = { x: e.touches[0].clientX, y: e.touches[0].clientY, start: KLINE.start, axis: null };
  }, { passive: true });
  host.addEventListener("touchmove", (e) => {
    if (!KLINE || !KLINE.drag || !e.touches[0]) return;
    const dx = e.touches[0].clientX - KLINE.drag.x;
    const dy = e.touches[0].clientY - KLINE.drag.y;
    if (KLINE.drag.axis === null && (Math.abs(dx) > 6 || Math.abs(dy) > 6)) {
      KLINE.drag.axis = Math.abs(dx) > Math.abs(dy) ? "x" : "y";
    }
    if (KLINE.drag.axis !== "x") return;                 // 纵向手势放给页面
    e.preventDefault();
    klinePan(KLINE.drag.start, Math.round(-dx / klineCandleW()));
    drawKline();
  }, { passive: false });
  host.addEventListener("touchend", () => { if (KLINE) KLINE.drag = null; });
}

// 绘制当前窗口的月K蜡烛图(红涨绿跌,符合 A 股/雪球习惯)
function drawKline() {
  const k = KLINE;
  if (!k) return;
  const host = k.host;
  const W = Math.max(320, host.clientWidth || 560), H = 264;
  const pad = { l: 8, r: 52, t: 26, b: 22 };
  const view = k.data.slice(k.start, k.start + k.count);
  let lo = Math.min(...view.map(d => d.l)), hi = Math.max(...view.map(d => d.h));
  if (hi === lo) hi = lo + 1;
  const span = hi - lo;
  lo -= span * 0.04; hi += span * 0.04;                  // 上下留白
  const innerW = W - pad.l - pad.r;
  const cw = innerW / k.count;                           // 每根蜡烛槽宽
  const bw = Math.max(1.5, cw * 0.62);                   // 实体宽度
  const cx = (j) => pad.l + cw * (j + 0.5);              // 第 j 根中心 x
  const iy = (v) => pad.t + (H - pad.t - pad.b) * (1 - (v - lo) / (hi - lo));
  const UP = "#eb5454", DOWN = "#2bbf72";

  let svg = `<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">`;
  // 横向网格 + 右侧价格刻度
  for (let g = 0; g <= 4; g++) {
    const y = pad.t + (H - pad.t - pad.b) * g / 4;
    const val = hi - (hi - lo) * g / 4;
    svg += `<line x1="${pad.l}" y1="${y.toFixed(1)}" x2="${W - pad.r}" y2="${y.toFixed(1)}" stroke="rgba(80,130,180,0.12)"/>`;
    svg += `<text x="${W - pad.r + 5}" y="${(y + 3).toFixed(1)}" font-size="10">${val.toFixed(val < 10 ? 2 : 0)}</text>`;
  }
  // 年份分隔(每年 1 月)
  view.forEach((d, j) => {
    if (d.m.endsWith("-01") || j === 0) {
      svg += `<line x1="${cx(j).toFixed(1)}" y1="${pad.t}" x2="${cx(j).toFixed(1)}" y2="${H - pad.b}" stroke="rgba(80,130,180,0.08)"/>`;
      svg += `<text x="${cx(j).toFixed(1)}" y="${H - 7}" text-anchor="middle" font-size="10">${d.m.slice(0, 4)}</text>`;
    }
  });
  // 蜡烛
  view.forEach((d, j) => {
    const up = d.c >= d.o;
    const col = up ? UP : DOWN;
    const x = cx(j);
    const yH = iy(d.h), yL = iy(d.l);
    const yO = iy(d.o), yC = iy(d.c);
    const top = Math.min(yO, yC), bot = Math.max(yO, yC);
    const bh = Math.max(1, bot - top);
    svg += `<line x1="${x.toFixed(1)}" y1="${yH.toFixed(1)}" x2="${x.toFixed(1)}" y2="${yL.toFixed(1)}" stroke="${col}" stroke-width="1"/>`;
    svg += `<rect x="${(x - bw / 2).toFixed(1)}" y="${top.toFixed(1)}" width="${bw.toFixed(1)}" height="${bh.toFixed(1)}" fill="${col}"/>`;
  });
  // 十字光标 + 顶部信息条
  let info = view.length ? view[view.length - 1] : null;
  if (k.hover >= k.start && k.hover < k.start + k.count) {
    const d = k.data[k.hover];
    info = d;
    const x = cx(k.hover - k.start), y = iy(d.c);
    svg += `<line x1="${x.toFixed(1)}" y1="${pad.t}" x2="${x.toFixed(1)}" y2="${H - pad.b}" stroke="rgba(220,235,255,0.35)" stroke-dasharray="3 3"/>`;
    svg += `<line x1="${pad.l}" y1="${y.toFixed(1)}" x2="${W - pad.r}" y2="${y.toFixed(1)}" stroke="rgba(220,235,255,0.35)" stroke-dasharray="3 3"/>`;
  }
  if (info) {
    const up = info.c >= info.o;
    const col = up ? UP : DOWN;
    const pct = info.o ? ((info.c - info.o) / info.o * 100) : 0;
    const dp = 2;
    svg += `<text x="${pad.l}" y="16" font-size="11" fill="#cfe0f5">${info.m}` +
      `  <tspan fill="${col}">开${info.o.toFixed(dp)} 高${info.h.toFixed(dp)} 低${info.l.toFixed(dp)} 收${info.c.toFixed(dp)} ` +
      `${pct >= 0 ? "+" : ""}${pct.toFixed(1)}%</tspan></text>`;
  }
  host.innerHTML = svg + `</svg>`;
}

// ---------- SVG charts (no deps) ----------
function bounds(values) {
  const nums = values.filter(v => v !== null && v !== undefined && !isNaN(v)).map(Number);
  if (!nums.length) return null;
  let lo = Math.min(...nums, 0), hi = Math.max(...nums);
  if (hi === lo) hi = lo + 1;
  return { lo, hi };
}

function lineChart(series, labels) {
  const W = 560, H = 240, pad = { l: 8, r: 12, t: 12, b: 26 };
  const all = series.flatMap(s => s.data);
  const b = bounds(all);
  if (!b || !labels.length) return `<svg viewBox="0 0 ${W} ${H}" style="width:100%"></svg>`;
  const ix = (i) => pad.l + (labels.length === 1 ? (W - pad.l - pad.r) / 2 : i * (W - pad.l - pad.r) / (labels.length - 1));
  const iy = (v) => pad.t + (H - pad.t - pad.b) * (1 - (v - b.lo) / (b.hi - b.lo));

  let svg = `<svg viewBox="0 0 ${W} ${H}" style="width:100%">`;
  // gridlines + y labels
  for (let g = 0; g <= 4; g++) {
    const y = pad.t + (H - pad.t - pad.b) * g / 4;
    const val = b.hi - (b.hi - b.lo) * g / 4;
    svg += `<line x1="${pad.l}" y1="${y}" x2="${W - pad.r}" y2="${y}" stroke="rgba(80,130,180,0.12)"/>`;
    svg += `<text x="${W - pad.r}" y="${y - 3}" text-anchor="end" font-size="9">${shortNum(val)}</text>`;
  }
  // x labels
  labels.forEach((lb, i) => { svg += `<text x="${ix(i)}" y="${H - 8}" text-anchor="middle" font-size="9">${lb}</text>`; });
  // series
  series.forEach(s => {
    let dpath = "", started = false;
    s.data.forEach((v, i) => {
      if (v === null || v === undefined || isNaN(v)) { started = false; return; }
      dpath += `${started ? "L" : "M"}${ix(i).toFixed(1)},${iy(Number(v)).toFixed(1)} `; started = true;
    });
    if (dpath) svg += `<path d="${dpath}" fill="none" stroke="${s.color}" stroke-width="2" />`;
    s.data.forEach((v, i) => {
      if (v === null || v === undefined || isNaN(v)) return;
      svg += `<circle cx="${ix(i).toFixed(1)}" cy="${iy(Number(v)).toFixed(1)}" r="2.4" fill="${s.color}"/>`;
    });
  });
  return svg + `</svg>`;
}

function areaChart(dates, closes) {
  const W = 420, H = 200, pad = { l: 6, r: 6, t: 12, b: 22 };
  // 过滤出有效点(closes 可能含 null,如后复权某些日期缺失)
  const pts = [];
  (closes || []).forEach((v, i) => { if (v !== null && v !== undefined && !isNaN(v)) pts.push({ i, v: Number(v) }); });
  if (pts.length < 2) return `<svg viewBox="0 0 ${W} ${H}" style="width:100%"></svg>`;
  const n = closes.length;
  const lo = Math.min(...pts.map(p => p.v)), hi = Math.max(...pts.map(p => p.v));
  const ix = (i) => pad.l + i * (W - pad.l - pad.r) / (n - 1 || 1);
  const iy = (v) => pad.t + (H - pad.t - pad.b) * (1 - (v - lo) / (hi - lo || 1));
  let line = "";
  pts.forEach((p, k) => { line += `${k ? "L" : "M"}${ix(p.i).toFixed(1)},${iy(p.v).toFixed(1)} `; });
  const area = line + `L${ix(pts[pts.length - 1].i).toFixed(1)},${H - pad.b} L${ix(pts[0].i).toFixed(1)},${H - pad.b} Z`;
  const up = pts[pts.length - 1].v >= pts[0].v;
  const col = up ? "#34d399" : "#fb7185";
  let svg = `<svg viewBox="0 0 ${W} ${H}" style="width:100%">`;
  svg += `<defs><linearGradient id="grad" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="${col}" stop-opacity="0.35"/>
      <stop offset="100%" stop-color="${col}" stop-opacity="0"/></linearGradient></defs>`;
  svg += `<path d="${area}" fill="url(#grad)"/>`;
  svg += `<path d="${line}" fill="none" stroke="${col}" stroke-width="1.8"/>`;
  svg += `<text x="${pad.l}" y="${H - 6}" font-size="9">${dates[0]}</text>`;
  svg += `<text x="${W - pad.r}" y="${H - 6}" text-anchor="end" font-size="9">${dates[dates.length - 1]}</text>`;
  return svg + `</svg>`;
}

// 趋势图:宽幅可横向滚动;三条绝对值线 + 净利同比%标注
function trendChart(t) {
  const years = t.years || [];
  const series = [
    { color: COLORS.assets, data: t.totalAssets || [] },
    { color: COLORS.revenue, data: t.revenue || [] },
    { color: COLORS.profit, data: t.netProfit || [] },
  ];
  const np = t.netProfit || [], npYoY = t.netProfitYoY || [];
  const PPY = 64, H = 250, pad = { l: 8, r: 50, t: 22, b: 26 };
  const all = series.flatMap(s => s.data).filter(v => v != null && !isNaN(v));
  if (!all.length || !years.length) return `<svg width="560" height="${H}"></svg>`;
  let lo = Math.min(...all, 0), hi = Math.max(...all);
  if (hi === lo) hi = lo + 1;
  const W = Math.max(560, pad.l + pad.r + (years.length - 1) * PPY);
  const ix = (i) => pad.l + i * PPY;
  const iy = (v) => pad.t + (H - pad.t - pad.b) * (1 - (v - lo) / (hi - lo));
  let svg = `<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">`;
  for (let g = 0; g <= 4; g++) {
    const y = pad.t + (H - pad.t - pad.b) * g / 4;
    const val = hi - (hi - lo) * g / 4;
    svg += `<line x1="${pad.l}" y1="${y}" x2="${W - pad.r}" y2="${y}" stroke="rgba(80,130,180,0.12)"/>`;
    svg += `<text x="${W - pad.r + 4}" y="${y + 3}" font-size="10">${shortNum(val)}</text>`;
  }
  years.forEach((yr, i) => { svg += `<text x="${ix(i)}" y="${H - 9}" text-anchor="middle" font-size="10">${yr}</text>`; });
  series.forEach(s => {
    let dpath = "", started = false;
    s.data.forEach((v, i) => {
      if (v === null || v === undefined || isNaN(v)) { started = false; return; }
      dpath += `${started ? "L" : "M"}${ix(i).toFixed(1)},${iy(+v).toFixed(1)} `; started = true;
    });
    if (dpath) svg += `<path d="${dpath}" fill="none" stroke="${s.color}" stroke-width="2"/>`;
    s.data.forEach((v, i) => { if (v != null && !isNaN(v)) svg += `<circle cx="${ix(i).toFixed(1)}" cy="${iy(+v).toFixed(1)}" r="2.3" fill="${s.color}"/>`; });
  });
  np.forEach((v, i) => {
    const g = npYoY[i];
    if (v == null || g == null) return;
    const col = g >= 0 ? "#34d399" : "#fb7185";
    svg += `<text x="${ix(i).toFixed(1)}" y="${(iy(+v) - 7).toFixed(1)}" text-anchor="middle" font-size="9" fill="${col}">${(g >= 0 ? "+" : "") + g.toFixed(0)}%</text>`;
  });
  return svg + `</svg>`;
}

function shortNum(v) {
  const a = Math.abs(v);
  if (a >= 10000) return (v / 10000).toFixed(1) + "万";
  if (a >= 1) return v.toFixed(0);
  return v.toFixed(1);
}

// ---------- boot ----------
loadWatchlist().catch(e => { $("#stock-list").innerHTML = `<div class="notice">加载失败: ${e}</div>`; });
