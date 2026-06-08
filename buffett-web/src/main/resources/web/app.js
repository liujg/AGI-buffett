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
  const groups = WATCHLIST.groups || [];
  const upd = WATCHLIST.updatedAt || WATCHLIST.updated_at;
  $("#wl-updated").textContent = upd ? `updated ${upd}` : "";
  const tabs = $("#group-tabs"); tabs.innerHTML = "";
  groups.forEach((g, i) => {
    const t = h(`<div class="tab" data-key="${g.key}">${g.name}<span class="count">${(g.items || []).length}</span></div>`);
    t.onclick = () => selectGroup(g.key);
    tabs.appendChild(t);
  });
  if (groups.length) selectGroup(activeGroup || groups[0].key);
}

function selectGroup(key) {
  activeGroup = key;
  document.querySelectorAll(".tab").forEach(t => t.classList.toggle("active", t.dataset.key === key));
  const group = (WATCHLIST.groups || []).find(g => g.key === key);
  const list = $("#stock-list"); list.innerHTML = "";
  (group.items || []).forEach(it => {
    const item = h(`<div class="stock-item" data-sym="${it.symbol}">
        <div class="nm"><b>${it.name || it.symbol}</b><span>${it.symbol}</span></div>
        <span class="badge ${it.market}">${it.market}</span>
      </div>`);
    item.onclick = () => { activeSymbol = it.symbol; markActive(); loadStock(it.symbol, it.market); };
    list.appendChild(item);
  });
  markActive();
}
function markActive() {
  document.querySelectorAll(".stock-item").forEach(s => s.classList.toggle("active", s.dataset.sym === activeSymbol));
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
  const yi = "亿" + curWord(s.currency);  // 亿元 / 亿港元 / 亿美元
  const kpis = `<div class="kpis">
    ${kpi("总资产", fmtYi(k.totalAssets), yi)}
    ${kpi("总负债", fmtYi(k.totalLiabilities), yi)}
    ${kpi("净资产", fmtYi(k.totalEquity), yi, "good")}
    ${kpi("货币资金", fmtYi(k.monetaryFunds), yi, "good")}
    ${kpi("资产负债率", ratio === null || ratio === undefined ? null : ratio, "%", ratio != null && ratio < 40 ? "good" : "warn")}
    ${kpi("营收(年)", fmtYi(k.revenue), yi)}
    ${kpi("归母净利(年)", fmtYi(k.netProfit), yi, "good")}
    ${kpi("股息率", dy === null || dy === undefined ? null : dy, "%", dy != null && dy >= 2 ? "good" : "")}
  </div>`;

  const t = s.trend || {};
  const trendSvg = lineChart(
    [
      { name: "总资产", color: COLORS.assets, data: t.totalAssets || [] },
      { name: "营收", color: COLORS.revenue, data: t.revenue || [] },
      { name: "归母净利", color: COLORS.profit, data: t.netProfit || [] },
    ], t.years || []);

  const hasHfq = s.price && Array.isArray(s.price.hfq);
  const priceCard = s.price
    ? `<div class="card">
         <h3>近期股价 · 收盘
           <span class="seg" style="margin-left:auto">
             <button class="seg-btn active" data-adj="qfq" onclick="setAdjust('qfq')">前复权</button>
             <button class="seg-btn ${hasHfq ? "" : "disabled"}" data-adj="hfq" ${hasHfq ? "onclick=\"setAdjust('hfq')\"" : ""}>后复权</button>
           </span>
         </h3>
         <div id="price-svg"></div>
         <div class="legend" id="price-meta"></div>
       </div>`
    : `<div class="card"><h3>近期股价</h3><div class="notice">无行情数据<br/>agibuffett fetch ${s.symbol} --only market</div></div>`;

  const dividends = s.dividends || [];
  const cashCell = (x) => x.cashPer10 != null
    ? `${x.cashPer10} <small>元/10股</small>`
    : (x.cashPerShare != null ? `${x.cashPerShare} <small>${curWord(x.currency || s.currency)}/股</small>` : "—");
  const divCard = dividends.length
    ? `<div class="card"><h3>近期分红</h3>
        <table class="divtab"><thead><tr><th>报告期</th><th>现金分红</th><th>股息率</th><th>除息日</th></tr></thead>
        <tbody>${dividends.map(x => `<tr>
            <td>${x.reportDate || "—"}</td>
            <td class="num">${cashCell(x)}</td>
            <td class="num">${x.yield != null ? x.yield + "%" : "—"}</td>
            <td>${x.exDate || "—"}</td></tr>`).join("")}</tbody></table></div>`
    : "";

  CUR = s; ADJ = "qfq";
  d.innerHTML = head + kpis + `<div class="charts">
      <div class="card"><h3>规模与盈利趋势 · 年报(亿元)</h3>${trendSvg}
        <div class="legend">
          <span><i style="background:${COLORS.assets}"></i>总资产</span>
          <span><i style="background:${COLORS.revenue}"></i>营收</span>
          <span><i style="background:${COLORS.profit}"></i>归母净利</span>
        </div></div>
      ${priceCard}
    </div>` + divCard;
  if (s.price) renderPrice();
}

// 前/后复权切换:仅重绘价格图,不重新请求
let ADJ = "qfq";
function setAdjust(adj) {
  if (!CUR || !CUR.price) return;
  if (adj === "hfq" && !Array.isArray(CUR.price.hfq)) return;
  ADJ = adj;
  document.querySelectorAll(".seg-btn").forEach(b => b.classList.toggle("active", b.dataset.adj === adj));
  renderPrice();
}
function renderPrice() {
  const p = CUR.price;
  const closes = ADJ === "hfq" ? p.hfq : p.qfq;
  const svg = $("#price-svg"); if (svg) svg.innerHTML = areaChart(p.dates, closes);
  const last = ADJ === "hfq" ? p.lastHfq : p.lastQfq;
  const meta = $("#price-meta");
  if (meta) meta.innerHTML = `<span>最新(${ADJ === "hfq" ? "后复权" : "前复权"}) <b style="color:var(--cyan)">${fmtYi(last)}</b> ${curWord(CUR.currency)}</span>
      <span>${p.dates[0]} → ${p.dates[p.dates.length - 1]}</span>`;
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

function shortNum(v) {
  const a = Math.abs(v);
  if (a >= 10000) return (v / 10000).toFixed(1) + "万";
  if (a >= 1) return v.toFixed(0);
  return v.toFixed(1);
}

// ---------- boot ----------
loadWatchlist().catch(e => { $("#stock-list").innerHTML = `<div class="notice">加载失败: ${e}</div>`; });
