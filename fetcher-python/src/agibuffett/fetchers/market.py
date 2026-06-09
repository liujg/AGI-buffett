"""历史行情抓取:A 股(东财,失败自动切新浪)/ 港股 / 美股,写为 CSV。"""
from __future__ import annotations

import logging
from typing import Dict, Optional

import akshare as ak
import pandas as pd

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
        if self.cfg.store_raw and self.cfg.adjust != "":
            try:
                self._fetch_one(code, market, us_code, "", "daily_raw.csv", full)
                result["raw"] = True
            except Exception:  # 不复权失败不影响主序列
                log.warning("[%s] 不复权(daily_raw.csv)抓取失败", code)
                result["raw"] = False
        return result

    def _download(self, market: str, code: str, us_code: str, adjust: str, sd: str, ed: str):
        """下载行情(英文列)。A 股优先东财,失败自动切新浪;港美股走东财。"""
        if market == "HK":
            # 港股:东财优先,失败兜底新浪(stock_hk_daily)
            try:
                return request_with_retry(
                    lambda: ak.stock_hk_hist(symbol=code, period=self.cfg.period,
                                             start_date=sd, end_date=ed, adjust=adjust),
                    what=f"[{code}] 日线{adjust or ''}(东财)", retries=3)
            except Exception as e:  # noqa: BLE001
                log.warning("[%s] 东财港股行情失败(%s),改用新浪源", code, type(e).__name__)
                return request_with_retry(
                    lambda: _sina_hk_daily(code, adjust, sd, ed),
                    what=f"[{code}] 日线{adjust or ''}(新浪)", retries=3)
        if market == "US":
            # 美股:东财优先,失败兜底新浪(stock_us_daily)
            try:
                return request_with_retry(
                    lambda: ak.stock_us_hist(symbol=us_code, period=self.cfg.period,
                                             start_date=sd, end_date=ed, adjust=adjust),
                    what=f"[{code}] 日线{adjust or ''}(东财)", retries=3)
            except Exception as e:  # noqa: BLE001
                log.warning("[%s] 东财美股行情失败(%s),改用新浪源", code, type(e).__name__)
                return request_with_retry(
                    lambda: _sina_us_daily(code, adjust, sd, ed),
                    what=f"[{code}] 日线{adjust or ''}(新浪)", retries=3)
        # A 股:东财优先,失败兜底新浪
        try:
            return request_with_retry(
                lambda: ak.stock_zh_a_hist(symbol=code, period=self.cfg.period,
                                           start_date=sd, end_date=ed, adjust=adjust),
                what=f"[{code}] 日线{adjust or ''}(东财)", retries=3)
        except Exception as e:  # noqa: BLE001
            log.warning("[%s] 东财行情失败(%s),改用新浪源", code, type(e).__name__)
            return request_with_retry(
                lambda: _sina_daily(code, adjust, sd, ed),
                what=f"[{code}] 日线{adjust or ''}(新浪)", retries=3)

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
        df = self._download(market, code, us_code, adjust, start_date, end_date)
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


def _sina_daily(code: str, adjust: str, sd: str, ed: str) -> "pd.DataFrame":
    """新浪源 A 股日线兜底(stock_zh_a_daily),归一化为英文列名。

    新浪本身就是英文列(date/open/high/low/close/volume/amount/turnover),
    与约定列名一致;date 统一为 YYYY-MM-DD。注:换手率为小数(非百分比)。
    """
    head = code[0]
    prefix = "sh" if head in ("6", "9") else ("bj" if head in ("4", "8") else "sz")
    df = ak.stock_zh_a_daily(symbol=prefix + code, start_date=sd, end_date=ed, adjust=adjust or "")
    if df is not None and not df.empty:
        df = df.copy()
        df["date"] = pd.to_datetime(df["date"]).dt.strftime("%Y-%m-%d")
    return df


def _sina_hk_daily(code: str, adjust: str, sd: str, ed: str) -> "pd.DataFrame":
    """新浪源港股日线兜底(stock_hk_daily),归一化为英文列名并按日期区间过滤。

    新浪返回全历史(无日期参数)且列为 date/open/high/low/close/volume/amount,
    与约定列名一致;这里按 [sd, ed] 截取(sd/ed 为 YYYYMMDD)。
    adjust 支持 ""(不复权)/ qfq(前复权)/ hfq(后复权)。
    """
    df = ak.stock_hk_daily(symbol=code, adjust=adjust or "")
    if df is None or df.empty:
        return df
    df = df.copy()
    df["date"] = pd.to_datetime(df["date"])
    lo = pd.to_datetime(sd, format="%Y%m%d")
    hi = pd.to_datetime(ed, format="%Y%m%d")
    df = df[(df["date"] >= lo) & (df["date"] <= hi)]
    df["date"] = df["date"].dt.strftime("%Y-%m-%d")
    return df.reset_index(drop=True)


def _sina_us_daily(code: str, adjust: str, sd: str, ed: str) -> "pd.DataFrame":
    """新浪源美股日线兜底(stock_us_daily),归一化为英文列名并按日期区间过滤。

    新浪返回全历史(无日期参数),列为 date/open/high/low/close/volume,与约定列名一致。
    仅支持不复权('')与前复权(qfq);后复权(hfq)新浪不提供,返回 None 跳过(非关键)。
    """
    if adjust == "hfq":
        return None
    df = ak.stock_us_daily(symbol=code, adjust=adjust or "")
    if df is None or df.empty:
        return df
    df = df.copy()
    df["date"] = pd.to_datetime(df["date"])
    lo = pd.to_datetime(sd, format="%Y%m%d")
    hi = pd.to_datetime(ed, format="%Y%m%d")
    df = df[(df["date"] >= lo) & (df["date"] <= hi)]
    df["date"] = df["date"].dt.strftime("%Y-%m-%d")
    return df.reset_index(drop=True)
