"""分红抓取,存为 fundamental/<symbol>/dividend.json。

- A 股:东财 stock_fhps_detail_em(每 10 股口径,含股息率)。
- 港股:东财 stock_hk_dividend_payout_em(每股派息,需解析方案文本)。
- 美股:AKShare 暂无现成接口,跳过。
"""
from __future__ import annotations

import logging
import re
from datetime import datetime
from typing import Any, Dict, List, Optional

import akshare as ak
import pandas as pd

from agibuffett.fetchers.base import normalize_symbol, request_with_retry
from agibuffett.storage import LocalStore

log = logging.getLogger(__name__)

# 东财列名 -> 归一化英文键
COLMAP = {
    "报告期": "report_date",
    "业绩披露日期": "disclose_date",
    "送转股份-送转总比例": "bonus_transfer_per_10",
    "送转股份-送股比例": "bonus_per_10",
    "送转股份-转股比例": "transfer_per_10",
    "现金分红-现金分红比例": "cash_per_10",
    "现金分红-现金分红比例描述": "plan_desc",
    "现金分红-股息率": "dividend_yield",
    "每股收益": "eps",
    "每股净资产": "bps",
    "股权登记日": "record_date",
    "除权除息日": "ex_date",
    "方案进度": "plan",
}
# 这些列是日期:报告期归一化为 YYYYMMDD,其余保留 ISO(YYYY-MM-DD)
_YMD_COLS = {"report_date"}
_ISO_DATE_COLS = {"disclose_date", "record_date", "ex_date"}


class DividendFetcher:
    def __init__(self, store: LocalStore):
        self.store = store

    def fetch_symbol(self, symbol: str, market: str = "A", full: bool = False) -> Dict[str, object]:
        market = (market or "A").upper()
        if market == "A":
            return self._fetch_a(symbol, full)
        if market == "HK":
            return self._fetch_hk(symbol, full)
        # 美股暂无分红数据源
        log.info("[%s] 美股暂无分红数据源,跳过", symbol)
        return {"dividend_rows": 0}

    def _fetch_a(self, symbol: str, full: bool) -> Dict[str, object]:
        code = normalize_symbol(symbol, "A")
        fetched_at = datetime.now().isoformat(timespec="seconds")
        df = request_with_retry(lambda: ak.stock_fhps_detail_em(symbol=code),
                                what=f"[{code}] 分红送配")
        if df is None or df.empty:
            log.info("[%s] 无分红数据", code)
            return {"dividend_rows": 0}

        records: List[Dict[str, Any]] = []
        for _, row in df.iterrows():
            rec: Dict[str, Any] = {"currency": "CNY"}
            for cn, en in COLMAP.items():
                if cn not in df.columns:
                    continue
                rec[en] = _clean(row[cn], en)
            records.append(rec)

        source = "akshare:stock_fhps_detail_em"
        if full:
            self.store.write_statement(code, "dividend", source, fetched_at, records)
            added, total = len(records), len(records)
        else:
            added, total = self.store.merge_statement(code, "dividend", source, fetched_at, records)
        log.info("[%s] 分红:新增 %d 期(共 %d 期)", code, added, total)

        latest_yield = _latest_yield(records)
        return {"dividend_rows": total, "dividend_added": added, "latest_yield": latest_yield}

    def _fetch_hk(self, symbol: str, full: bool) -> Dict[str, object]:
        code = normalize_symbol(symbol, "HK")
        fetched_at = datetime.now().isoformat(timespec="seconds")
        df = request_with_retry(lambda: ak.stock_hk_dividend_payout_em(symbol=code),
                                what=f"[{code}] 港股分红")
        if df is None or df.empty:
            log.info("[%s] 无港股分红数据", code)
            return {"dividend_rows": 0}

        records: List[Dict[str, Any]] = []
        for _, row in df.iterrows():
            plan = _txt(row.get("分红方案"))
            cash, currency = _parse_hk_plan(plan)
            records.append({
                "report_date": _txt(row.get("财政年度")),  # 财政年度,如 "2025"
                "cash_per_share": cash,                     # 每股派息(原币种)
                "currency": currency,
                "plan_desc": plan,
                "type": _txt(row.get("分配类型")),
                "ex_date": _iso(row.get("除净日")),
                "pay_date": _iso(row.get("发放日")),
                "disclose_date": _iso(row.get("最新公告日期")),
            })

        source = "akshare:stock_hk_dividend_payout_em"
        if full:
            self.store.write_statement(code, "dividend", source, fetched_at, records)
            added, total = len(records), len(records)
        else:
            added, total = self.store.merge_statement(code, "dividend", source, fetched_at, records)
        log.info("[%s] 港股分红:新增 %d 期(共 %d 期)", code, added, total)
        return {"dividend_rows": total, "dividend_added": added}


def _parse_hk_plan(plan: Optional[str]):
    """从港股分红方案文本解析**每股现金派息**与币种,统一取交易币种(港币)口径。

    港股以港币交易,故优先取港币金额,使其与行情(港币)一致:
    - '每股派港币2.4元'                       -> (2.4, 'HKD')
    - '每股派人民币2.276元(相当于港币2.52元)' -> (2.52, 'HKD')   # 取换算后的港币
    - '每股派美元0.1元'                        -> (0.1, 'USD')

    **实物分派(以股代息)** 不是现金分红,返回 (None, ...) 不计入:
    - '每21股腾讯股份分派1股京东集团A类普通股股份'            -> (None, 'HKD')
    - '每10股分派1股美团B类普通股股份(相当于每股派18.13港元)' -> (None, 'HKD')
    早期 bug:兜底"取第一处数字"会把比例数(如"每21股…")误当成 21 港元,导致虚高。
    """
    if not plan:
        return None, "HKD"
    p = plan.replace(",", "").replace(" ", "")
    # 实物分派股份(分派…股份/普通股,即以股代息),非现金分红,直接排除
    if "分派" in p and ("股份" in p or "普通股" in p):
        return None, "HKD"

    def amount(*patterns):
        for pat in patterns:
            m = re.search(pat, p)
            if m:
                return float(m.group(1))
        return None

    # 1) 港币(交易币种)优先:数字在 港币/港元 之后或之前
    hk = amount(r"港[币元](\d+(?:\.\d+)?)", r"(\d+(?:\.\d+)?)港[元币]")
    if hk is not None:
        return hk, "HKD"
    # 2) 美元
    us = amount(r"(?:美元|美金|US\$)(\d+(?:\.\d+)?)", r"(\d+(?:\.\d+)?)美元")
    if us is not None:
        return us, "USD"
    # 3) 人民币(无港币换算时)
    cn = amount(r"(?:人民币|￥)(\d+(?:\.\d+)?)", r"(\d+(?:\.\d+)?)人民币")
    if cn is not None:
        return cn, "CNY"
    # 4) 通用现金:"派…X元"(仅在确为派现金时,不抓比例数)
    cash = amount(r"派(\d+(?:\.\d+)?)元")
    if cash is not None:
        return cash, "HKD"
    # 解析不出现金金额(如纯文字说明)→ 不计为现金分红
    return None, "HKD"


def _txt(v: Any) -> Optional[str]:
    if v is None or (_scalar(v) and pd.isna(v)):
        return None
    s = str(v).strip()
    return s or None


def _iso(v: Any) -> Optional[str]:
    if v is None or (_scalar(v) and pd.isna(v)):
        return None
    try:
        return pd.Timestamp(v).strftime("%Y-%m-%d")
    except Exception:
        s = str(v).strip().split(" ")[0]
        return s or None


def _clean(v: Any, key: str) -> Any:
    # 缺失值(None / NaN / NaT)-> None
    if v is None or (_scalar(v) and pd.isna(v)):
        return None
    # 日期类:报告期 -> YYYYMMDD,其余 -> ISO YYYY-MM-DD
    if key in _YMD_COLS or key in _ISO_DATE_COLS:
        try:
            iso = pd.Timestamp(v).strftime("%Y-%m-%d")
        except Exception:
            return None
        return iso.replace("-", "") if key in _YMD_COLS else iso
    # 数值:转 python 原生标量
    if hasattr(v, "item"):
        try:
            return v.item()
        except (ValueError, TypeError):
            pass
    return v


def _scalar(v: Any) -> bool:
    return not hasattr(v, "__len__") or isinstance(v, str)


def _latest_yield(records: List[Dict[str, Any]]):
    """最近一期有股息率的记录(records 已按报告期降序)。"""
    for r in records:
        y = r.get("dividend_yield")
        if y is not None:
            return y
    return None
