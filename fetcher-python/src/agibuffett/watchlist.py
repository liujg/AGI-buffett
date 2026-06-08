"""自选股清单 `data/watchlist.json` 的读写。

分组(自选 / 重点观察 / 机会点)与多市场(A/HK/US),被抓取端与分析端共用。
契约见 `data/README.md`。
"""
from __future__ import annotations

import json
import os
from datetime import date
from pathlib import Path
from typing import Dict, List, Optional

# 内置分组:key -> 显示名(可自行增删,key 用作稳定标识)
DEFAULT_GROUPS = [
    ("watch", "自选"),
    ("focus", "重点观察"),
    ("opportunity", "机会点"),
]

VALID_MARKETS = ("A", "HK", "US")


class WatchlistStore:
    def __init__(self, data_dir: Path):
        self.path = Path(data_dir) / "watchlist.json"

    # ---- 读写 ----
    def load(self) -> Dict:
        if not self.path.exists():
            return self._default()
        with open(self.path, "r", encoding="utf-8") as f:
            return json.load(f)

    def save(self, wl: Dict) -> Path:
        wl["updated_at"] = date.today().isoformat()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".json.tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(json.dumps(wl, ensure_ascii=False, indent=2))
        os.replace(tmp, self.path)
        return self.path

    @staticmethod
    def _default() -> Dict:
        return {
            "version": 1,
            "updated_at": date.today().isoformat(),
            "groups": [{"key": k, "name": n, "items": []} for k, n in DEFAULT_GROUPS],
        }

    # ---- 查询 ----
    def group(self, wl: Dict, key: str) -> Optional[Dict]:
        for g in wl.get("groups", []):
            if g.get("key") == key:
                return g
        return None

    def items(self, key: Optional[str] = None, market: Optional[str] = None) -> List[Dict]:
        """返回清单条目;可按分组 key 和市场过滤。"""
        wl = self.load()
        groups = wl.get("groups", [])
        if key:
            groups = [g for g in groups if g.get("key") == key]
        out: List[Dict] = []
        for g in groups:
            for it in g.get("items", []):
                if market and it.get("market", "A") != market:
                    continue
                out.append(it)
        return out

    # ---- 维护 ----
    def add(self, symbol: str, group: str, market: str = "A", name: str = "") -> str:
        market = market.upper()
        if market not in VALID_MARKETS:
            raise ValueError("market 必须是 %s 之一" % "/".join(VALID_MARKETS))
        wl = self.load()
        g = self.group(wl, group)
        if g is None:
            display = dict(DEFAULT_GROUPS).get(group, group)
            g = {"key": group, "name": display, "items": []}
            wl.setdefault("groups", []).append(g)
        for it in g["items"]:
            if it.get("symbol") == symbol and it.get("market", "A") == market:
                if name and not it.get("name"):
                    it["name"] = name
                self.save(wl)
                return "exists"
        g["items"].append({"symbol": symbol, "market": market, "name": name})
        self.save(wl)
        return "added"

    def remove(self, symbol: str, group: Optional[str] = None) -> int:
        """从指定分组(或全部分组)移除某代码,返回移除条数。"""
        wl = self.load()
        removed = 0
        for g in wl.get("groups", []):
            if group and g.get("key") != group:
                continue
            before = len(g.get("items", []))
            g["items"] = [it for it in g.get("items", []) if it.get("symbol") != symbol]
            removed += before - len(g["items"])
        if removed:
            self.save(wl)
        return removed
