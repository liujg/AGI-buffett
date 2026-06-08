"""配置加载。"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional

import yaml

DEFAULT_CONFIG_PATH = Path(__file__).resolve().parents[2] / "config.yaml"


@dataclass
class MarketConfig:
    period: str = "daily"
    adjust: str = "qfq"        # 主序列(daily.csv)的复权方式
    start_date: str = "20100101"
    end_date: str = ""
    store_hfq: bool = True     # 额外存一份后复权 daily_hfq.csv,供前端切换


@dataclass
class RateLimitConfig:
    """限流:控制请求间隔,避免被第三方数据源封禁。"""
    min_interval: float = 1.0   # 任意两次请求的最小间隔(秒)
    jitter: float = 0.5         # 叠加的随机抖动上限(秒)


@dataclass
class Config:
    data_dir: Path
    market: MarketConfig = field(default_factory=MarketConfig)
    rate_limit: RateLimitConfig = field(default_factory=RateLimitConfig)
    symbols: List[str] = field(default_factory=list)

    @property
    def fundamental_dir(self) -> Path:
        return self.data_dir / "fundamental"

    @property
    def market_dir(self) -> Path:
        return self.data_dir / "market"


def load_config(path: Optional[str] = None) -> Config:
    """从 YAML 加载配置;`data_dir` 相对路径按配置文件所在目录解析。"""
    cfg_path = Path(path).resolve() if path else DEFAULT_CONFIG_PATH
    raw = {}
    if cfg_path.exists():
        with open(cfg_path, "r", encoding="utf-8") as f:
            raw = yaml.safe_load(f) or {}

    data_dir_raw = raw.get("data_dir", "../data")
    data_dir = Path(data_dir_raw)
    if not data_dir.is_absolute():
        data_dir = (cfg_path.parent / data_dir).resolve()

    market_raw = raw.get("market", {}) or {}
    market = MarketConfig(
        period=market_raw.get("period", "daily"),
        adjust=market_raw.get("adjust", "qfq"),
        start_date=str(market_raw.get("start_date", "20100101")),
        end_date=str(market_raw.get("end_date", "") or ""),
        store_hfq=bool(market_raw.get("store_hfq", True)),
    )

    rl_raw = raw.get("rate_limit", {}) or {}
    rate_limit = RateLimitConfig(
        min_interval=float(rl_raw.get("min_interval", 1.0)),
        jitter=float(rl_raw.get("jitter", 0.5)),
    )

    return Config(
        data_dir=data_dir,
        market=market,
        rate_limit=rate_limit,
        symbols=[str(s) for s in (raw.get("symbols") or [])],
    )
