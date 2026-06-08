"""历史行情抓取:A 股日线(东财),写为 CSV。"""
from __future__ import annotations

import logging
from typing import Dict, Optional

import akshare as ak

from agibuffett.config import MarketConfig
from agibuffett.fetchers.base import normalize_symbol, request_with_retry
from agibuffett.storage import LocalStore

log = logging.getLogger(__name__)

# AKShare stock_zh_a_hist 中文列名 -> 约定的英文列名
COLUMN_MAP = {
    "日期": "date",
    "开盘": "open",
    "收盘": "close",
    "最高": "high",
    "最低": "low",
    "成交量": "volume",
    "成交额": "amount",
    "振幅": "amplitude",
    "涨跌幅": "pct_change",
    "涨跌额": "change",
    "换手率": "turnover",
}


class MarketFetcher:
    def __init__(self, store: LocalStore, market_cfg: MarketConfig):
        self.store = store
        self.cfg = market_cfg

    def fetch_symbol(self, symbol: str, market: str = "A", full: bool = False,
                     us_code: str = None) -> Dict[str, object]:
        """抓取日线并写盘(A/HK/US)。

        主序列(``cfg.adjust``,默认前复权)写入 ``daily.csv``;若 ``cfg.store_hfq``,
        再额外抓一份后复权写入 ``daily_hfq.csv`` 供前端切换。默认增量续抓。
        美股行情需东财代码(如 105.AAPL),由 ``us_code`` 传入或从本地 meta 读取。
        """
        market = (market or "A").upper()
        code = normalize_symbol(symbol, market)
        if market == "US" and not us_code:
            us_code = (self.store.read_meta(code) or {}).get("us_code")
            if not us_code:
                log.warning("[%s] 美股行情需先抓基本面以取得东财代码,本次跳过行情", code)
                existing = self.store.read_market_daily(code)
                return {"daily_rows": 0 if existing is None else len(existing), "added": 0}

        result = self._fetch_one(code, market, us_code, self.cfg.adjust, "daily.csv", full)
        if self.cfg.store_hfq and self.cfg.adjust != "hfq":
            try:
                self._fetch_one(code, market, us_code, "hfq", "daily_hfq.csv", full)
                result["hfq"] = True
            except Exception:  # 后复权失败不影响主序列
                log.warning("[%s] 后复权(daily_hfq.csv)抓取失败", code)
                result["hfq"] = False
        return result

    def _hist(self, market: str, code: str, us_code: str, adjust: str, sd: str, ed: str):
        if market == "HK":
            return ak.stock_hk_hist(symbol=code, period=self.cfg.period,
                                    start_date=sd, end_date=ed, adjust=adjust)
        if market == "US":
            return ak.stock_us_hist(symbol=us_code, period=self.cfg.period,
                                    start_date=sd, end_date=ed, adjust=adjust)
        return ak.stock_zh_a_hist(symbol=code, period=self.cfg.period,
                                  start_date=sd, end_date=ed, adjust=adjust)

    def _fetch_one(self, code: str, market: str, us_code: str,
                   adjust: str, filename: str, full: bool) -> Dict[str, object]:
        start_date = self.cfg.start_date
        last = None if full else self.store.last_market_date(code, filename)
        if last:
            # 从最后一天起重抓(含当天,以防当日数据有修正);去重时新值覆盖
            start_date = last.replace("-", "")
        end_date = self.cfg.end_date or _today()

        log.info("[%s] 抓取日线 %s~%s adjust=%s 市场=%s -> %s%s",
                 code, start_date, end_date, adjust or "none", market, filename,
                 "" if full else "(增量)")
        df = request_with_retry(
            lambda: self._hist(market, code, us_code, adjust, start_date, end_date),
            what=f"[{code}] 日线{adjust or ''}",
        )
        if df is None or df.empty:
            existing = self.store.read_market_daily(code, filename)
            total = 0 if existing is None else len(existing)
            log.info("[%s] %s 无新行情(已是最新)", code, filename)
            return {"daily_rows": total, "added": 0}

        df = df.rename(columns=COLUMN_MAP).sort_values("date").reset_index(drop=True)
        if full:
            self.store.write_market_daily(code, df, filename)
            added, total = len(df), len(df)
        else:
            added, total = self.store.append_market_daily(code, df, filename)

        log.info("[%s] %s:新增 %d 行(共 %d 行)", code, filename, added, total)
        full_df = self.store.read_market_daily(code, filename)
        return {
            "daily_rows": total,
            "added": added,
            "start": str(full_df["date"].iloc[0]) if full_df is not None and len(full_df) else "",
            "end": str(full_df["date"].iloc[-1]) if full_df is not None and len(full_df) else "",
        }


def _today() -> str:
    from datetime import date
    return date.today().strftime("%Y%m%d")
