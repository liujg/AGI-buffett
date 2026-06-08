"""基本面抓取:资产负债表 / 利润表 / 现金流量表。

- A 股:东财按报告期接口(stock_*_by_report_em),宽表(每列一个科目)。
- 港股 / 美股:东财 stock_financial_(hk|us)_report_em,长表(每行 = 报告期×科目),
  统一透视成与 A 股相同的 records 结构。
"""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Dict, List, Tuple

import akshare as ak

from agibuffett.fetchers.base import (
    normalize_symbol,
    request_with_retry,
    us_hist_code,
    with_exchange_prefix,
)
from agibuffett.storage import LocalStore, dataframe_to_records, long_dataframe_to_records

log = logging.getLogger(__name__)

# A 股 EM 宽表里属于元信息、不计入财务行项目的列
META_COLS = {
    "SECUCODE", "SECURITY_CODE", "SECURITY_NAME_ABBR", "ORG_CODE", "ORG_TYPE",
    "REPORT_DATE", "REPORT_TYPE", "REPORT_DATE_NAME", "SECURITY_TYPE_CODE",
    "NOTICE_DATE", "UPDATE_DATE", "CURRENCY", "OPINION_TYPE", "LISTING_STATE",
}

# A 股:statement -> (AKShare 函数名, 中文标签)
A_STATEMENTS: Dict[str, Tuple[str, str]] = {
    "balance_sheet": ("stock_balance_sheet_by_report_em", "资产负债表"),
    "income_statement": ("stock_profit_sheet_by_report_em", "利润表"),
    "cash_flow": ("stock_cash_flow_sheet_by_report_em", "现金流量表"),
}

# 港股 / 美股:statement -> 接口 symbol 参数(报表中文名)。注意利润表名不同:
#   港股=利润表,美股=综合损益表。
STATEMENT_CN = {
    "HK": {"balance_sheet": "资产负债表", "income_statement": "利润表", "cash_flow": "现金流量表"},
    "US": {"balance_sheet": "资产负债表", "income_statement": "综合损益表", "cash_flow": "现金流量表"},
}

DATE_COL = "REPORT_DATE"


class FundamentalFetcher:
    def __init__(self, store: LocalStore):
        self.store = store

    def fetch_symbol(self, symbol: str, market: str = "A", full: bool = False) -> Dict[str, object]:
        """按市场路由抓取三张报表并写盘,返回 meta 信息。"""
        market = (market or "A").upper()
        if market == "A":
            return self._fetch_a(symbol, full)
        return self._fetch_overseas(symbol, market, full)

    # ---- A 股:宽表 ----
    def _fetch_a(self, symbol: str, full: bool) -> Dict[str, object]:
        code = normalize_symbol(symbol, "A")
        em_symbol = with_exchange_prefix(code)
        fetched_at = datetime.now().isoformat(timespec="seconds")
        name = ""
        written: List[str] = []
        added_periods: Dict[str, int] = {}

        for statement, (func_name, label) in A_STATEMENTS.items():
            func = getattr(ak, func_name)
            log.info("[%s] 抓取%s (%s)", code, label, func_name)
            df = request_with_retry(lambda f=func: f(symbol=em_symbol), what=f"[{code}] {label}")
            if df is None or df.empty:
                log.warning("[%s] %s 返回空", code, label)
                continue
            if not name and "SECURITY_NAME_ABBR" in df.columns and len(df):
                name = str(df.iloc[0]["SECURITY_NAME_ABBR"])
            item_df = df.drop(columns=[c for c in META_COLS if c in df.columns and c != DATE_COL],
                              errors="ignore")
            records = dataframe_to_records(item_df, date_col=DATE_COL)
            added, total = self._persist(code, statement, f"akshare:{func_name}", fetched_at, records, full)
            log.info("[%s] %s:新增 %d 期(共 %d 期)", code, label, added, total)
            added_periods[statement] = added
            written.append(statement)

        return {
            "symbol": code, "name": name, "source": "akshare",
            "fetched_at": fetched_at, "statements": written, "added_periods": added_periods,
        }

    # ---- 港股 / 美股:长表 ----
    def _fetch_overseas(self, symbol: str, market: str, full: bool) -> Dict[str, object]:
        code = normalize_symbol(symbol, market)
        fetched_at = datetime.now().isoformat(timespec="seconds")
        if market == "HK":
            func_name, indicator, name_col = "stock_financial_hk_report_em", "报告期", "STD_ITEM_NAME"
        else:  # US
            func_name, indicator, name_col = "stock_financial_us_report_em", "年报", "ITEM_NAME"
        func = getattr(ak, func_name)
        cn_map = STATEMENT_CN[market]

        name = ""
        secucode = ""
        written: List[str] = []
        added_periods: Dict[str, int] = {}

        for statement, cn in cn_map.items():
            log.info("[%s] 抓取%s (%s/%s)", code, cn, func_name, indicator)
            df = request_with_retry(
                lambda c=cn: func(stock=code, symbol=c, indicator=indicator),
                what=f"[{code}] {cn}")
            if df is None or df.empty:
                log.warning("[%s] %s 返回空", code, cn)
                continue
            if not name and "SECURITY_NAME_ABBR" in df.columns and len(df):
                name = str(df.iloc[0]["SECURITY_NAME_ABBR"])
            if not secucode and "SECUCODE" in df.columns and len(df):
                secucode = str(df.iloc[0]["SECUCODE"])
            records = long_dataframe_to_records(df, date_col=DATE_COL, name_col=name_col)
            added, total = self._persist(code, statement, f"akshare:{func_name}", fetched_at, records, full)
            log.info("[%s] %s:新增 %d 期(共 %d 期)", code, cn, added, total)
            added_periods[statement] = added
            written.append(statement)

        meta: Dict[str, object] = {
            "symbol": code, "name": name, "source": "akshare",
            "fetched_at": fetched_at, "statements": written, "added_periods": added_periods,
        }
        if market == "US" and secucode:
            meta["us_code"] = us_hist_code(secucode)  # 供美股行情接口使用,如 105.AAPL
        return meta

    def _persist(self, code, statement, source, fetched_at, records, full) -> Tuple[int, int]:
        if full:
            self.store.write_statement(code, statement, source, fetched_at, records)
            return len(records), len(records)
        return self.store.merge_statement(code, statement, source, fetched_at, records)
