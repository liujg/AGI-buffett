"""本地文件存储:把抓取结果按 `data/README.md` 约定写入磁盘。

写入端唯一实现;对应的 Java 读取端见
buffett-common/.../storage/LocalStore.java。
"""
from __future__ import annotations

import json
import math
import os
from collections import OrderedDict
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import pandas as pd

# daily.csv 的固定表头(英文,归一化自 AKShare 中文列名)
MARKET_COLUMNS = [
    "date", "open", "close", "high", "low",
    "volume", "amount", "amplitude", "pct_change", "change", "turnover",
]


class LocalStore:
    def __init__(self, data_dir: Path):
        self.data_dir = Path(data_dir)
        self.fundamental_dir = self.data_dir / "fundamental"
        self.market_dir = self.data_dir / "market"

    # ---- 路径布局 ----
    def symbol_fundamental_dir(self, symbol: str) -> Path:
        return self.fundamental_dir / symbol

    def symbol_market_dir(self, symbol: str) -> Path:
        return self.market_dir / symbol

    # ---- 写报表 (JSON) ----
    def write_statement(
        self,
        symbol: str,
        statement: str,
        source: str,
        fetched_at: str,
        records: List[Dict[str, Any]],
    ) -> Path:
        out_dir = self.symbol_fundamental_dir(symbol)
        out_dir.mkdir(parents=True, exist_ok=True)
        payload = {
            "symbol": symbol,
            "statement": statement,
            "source": source,
            "fetched_at": fetched_at,
            "records": records,
        }
        path = out_dir / f"{statement}.json"
        _atomic_write_text(path, json.dumps(payload, ensure_ascii=False, indent=2))
        return path

    def read_statement(self, symbol: str, statement: str) -> Optional[Dict[str, Any]]:
        """读取已有报表 JSON;不存在返回 None。"""
        path = self.symbol_fundamental_dir(symbol) / f"{statement}.json"
        if not path.exists():
            return None
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)

    def merge_statement(
        self,
        symbol: str,
        statement: str,
        source: str,
        fetched_at: str,
        new_records: List[Dict[str, Any]],
    ) -> Tuple[int, int]:
        """把新抓的记录按报告期合并进已有文件(增量):
        - 已有报告期保留;新出现的报告期追加;
        - 同一报告期若再次抓到(可能为重述数据)则以新值覆盖。
        返回 (新增期数, 合并后总期数)。
        """
        existing = self.read_statement(symbol, statement)
        by_key: "OrderedDict[tuple, Dict[str, Any]]" = OrderedDict()
        if existing:
            for r in existing.get("records", []):
                by_key[_merge_key(r)] = r
        existing_keys = set(by_key.keys())

        added = 0
        for r in new_records:
            key = _merge_key(r)
            if key not in existing_keys:
                added += 1
            by_key[key] = r  # 覆盖以反映重述

        merged = sorted(by_key.values(),
                        key=lambda r: r.get("report_date", ""), reverse=True)
        self.write_statement(symbol, statement, source, fetched_at, merged)
        return added, len(merged)

    def read_meta(self, symbol: str) -> Optional[Dict[str, Any]]:
        """读取已有 meta.json;不存在返回 None。"""
        path = self.symbol_fundamental_dir(symbol) / "meta.json"
        if not path.exists():
            return None
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)

    def write_meta(self, symbol: str, meta: Dict[str, Any]) -> Path:
        out_dir = self.symbol_fundamental_dir(symbol)
        out_dir.mkdir(parents=True, exist_ok=True)
        path = out_dir / "meta.json"
        _atomic_write_text(path, json.dumps(meta, ensure_ascii=False, indent=2))
        return path

    # ---- 写行情 (CSV) ----
    # filename 区分复权序列:daily.csv(主)/ daily_hfq.csv(后复权)等
    def write_market_daily(self, symbol: str, df: pd.DataFrame, filename: str = "daily.csv") -> Path:
        out_dir = self.symbol_market_dir(symbol)
        out_dir.mkdir(parents=True, exist_ok=True)
        path = out_dir / filename
        # 仅保留约定的列,缺失列补空,保证表头稳定
        cols = [c for c in MARKET_COLUMNS if c in df.columns]
        df = df[cols]
        tmp = path.with_suffix(".csv.tmp")
        df.to_csv(tmp, index=False, encoding="utf-8")
        os.replace(tmp, path)
        return path

    def read_market_daily(self, symbol: str, filename: str = "daily.csv") -> Optional[pd.DataFrame]:
        """读取已有日线 CSV;不存在返回 None。date 列保持为字符串。"""
        path = self.symbol_market_dir(symbol) / filename
        if not path.exists():
            return None
        return pd.read_csv(path, dtype={"date": str})

    def last_market_date(self, symbol: str, filename: str = "daily.csv") -> Optional[str]:
        """已有日线的最后一个交易日('YYYY-MM-DD');无数据返回 None。"""
        df = self.read_market_daily(symbol, filename)
        if df is None or df.empty:
            return None
        return str(df["date"].iloc[-1])

    def append_market_daily(self, symbol: str, df_new: pd.DataFrame,
                            filename: str = "daily.csv") -> Tuple[int, int]:
        """把新行情按 date 追加进已有 CSV(增量):去重(新值覆盖同日)、按日期升序。
        返回 (新增行数, 合并后总行数)。
        """
        existing = self.read_market_daily(symbol, filename)
        if existing is None or existing.empty:
            self.write_market_daily(symbol, df_new, filename)
            return len(df_new), len(df_new)

        before = set(existing["date"].astype(str))
        combined = pd.concat([existing, df_new], ignore_index=True)
        combined["date"] = combined["date"].astype(str)
        combined = (combined
                    .drop_duplicates(subset=["date"], keep="last")
                    .sort_values("date")
                    .reset_index(drop=True))
        self.write_market_daily(symbol, combined, filename)
        added = len(set(combined["date"]) - before)
        return added, len(combined)


def _merge_key(r: Dict[str, Any]) -> tuple:
    """记录去重键。报表只有 report_date;港股分红同一财政年度有多笔(中期/年度/特别),
    需叠加 type 与 ex_date 才能区分,否则会被按年度折叠成一笔(漏掉末期派息)。"""
    return (r.get("report_date", ""), r.get("type"), r.get("ex_date"))


def dataframe_to_records(df: pd.DataFrame, date_col: str) -> List[Dict[str, Any]]:
    """把报表 DataFrame 转成约定的 records 结构。

    每行 -> {"report_date": <date_col 值>, "items": {列名: 数值/None}}。
    NaN/Inf 归一化为 None,以产出合法 JSON。
    """
    records: List[Dict[str, Any]] = []
    for _, row in df.iterrows():
        report_date = _norm_date(row.get(date_col))
        items: Dict[str, Any] = {}
        for col in df.columns:
            if col == date_col:
                continue
            items[str(col)] = _clean_value(row[col])
        records.append({"report_date": report_date, "items": items})
    return records


def long_dataframe_to_records(
    df: pd.DataFrame, date_col: str, name_col: str, value_col: str = "AMOUNT"
) -> List[Dict[str, Any]]:
    """把 **长表**(每行 = 报告期 × 科目 -> 金额,港股/美股报表格式)透视成约定的
    records 结构:按报告期分组,items = {科目名: 金额};报告期降序。
    """
    by_date: "OrderedDict[str, Dict[str, Any]]" = OrderedDict()
    for _, row in df.iterrows():
        rd = _norm_date(row.get(date_col))
        by_date.setdefault(rd, {})[str(row.get(name_col))] = _clean_value(row.get(value_col))
    records = [{"report_date": rd, "items": items} for rd, items in by_date.items()]
    records.sort(key=lambda r: r["report_date"], reverse=True)
    return records


def _clean_value(v: Any) -> Any:
    if v is None:
        return None
    if isinstance(v, float) and (math.isnan(v) or math.isinf(v)):
        return None
    # pandas/numpy 标量 -> python 原生
    if hasattr(v, "item"):
        try:
            v = v.item()
        except (ValueError, TypeError):
            pass
    if isinstance(v, float) and (math.isnan(v) or math.isinf(v)):
        return None
    return v


def _norm_date(v: Any) -> str:
    if v is None:
        return ""
    s = str(v).strip()
    # 形如 2023-12-31 00:00:00 -> 20231231;2023-12-31 -> 20231231
    s = s.split(" ")[0].replace("-", "")
    return s


def _atomic_write_text(path: Path, text: str) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)
