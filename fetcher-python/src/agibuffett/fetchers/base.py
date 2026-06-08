"""抓取相关的公共工具。"""
from __future__ import annotations

import logging
import random
import threading
import time
from typing import Callable, TypeVar

log = logging.getLogger(__name__)

T = TypeVar("T")


class RateLimiter:
    """限流器:保证任意两次请求之间至少间隔 ``min_interval`` 秒,并叠加
    ``[0, jitter]`` 的随机抖动,避免请求过密被第三方(如东财)限流/封禁。

    线程安全;``min_interval <= 0`` 时不限流(直接放行)。
    """

    def __init__(self, min_interval: float = 1.0, jitter: float = 0.5):
        self.min_interval = max(0.0, float(min_interval))
        self.jitter = max(0.0, float(jitter))
        self._last = 0.0
        self._lock = threading.Lock()

    def acquire(self) -> None:
        if self.min_interval <= 0 and self.jitter <= 0:
            return
        with self._lock:
            now = time.monotonic()
            target = self._last + self.min_interval + random.uniform(0, self.jitter)
            wait = target - now
            if wait > 0:
                log.debug("限流:等待 %.2fs", wait)
                time.sleep(wait)
            self._last = time.monotonic()


# 全局限流器:默认不限流,由 CLI 按配置 configure_rate_limiter() 后生效。
_LIMITER = RateLimiter(min_interval=0.0, jitter=0.0)


def configure_rate_limiter(min_interval: float, jitter: float = 0.5) -> None:
    """设置全局限流参数(在开始抓取前调用一次)。"""
    global _LIMITER
    _LIMITER = RateLimiter(min_interval=min_interval, jitter=jitter)
    log.info("限流已启用:间隔 >= %.2fs (+抖动 <= %.2fs)", _LIMITER.min_interval, _LIMITER.jitter)


def request_with_retry(fn: Callable[[], T], *, what: str,
                       retries: int = 4, backoff: float = 2.0) -> T:
    """限流 + 指数退避重试,缓解数据源(东财)的连接重置 / 限流。"""
    last = None
    for attempt in range(1, retries + 1):
        _LIMITER.acquire()
        try:
            return fn()
        except Exception as e:  # noqa: BLE001 — 数据源异常类型多样
            last = e
            wait = backoff * attempt
            log.warning("%s 第 %d/%d 次失败: %s;%.0fs 后重试",
                        what, attempt, retries, type(e).__name__, wait)
            if attempt < retries:
                time.sleep(wait)
    raise last  # type: ignore[misc]


def normalize_symbol(symbol: str, market: str = "A") -> str:
    """按市场归一化代码:
    - A:6 位(去掉 SH/SZ/BJ 前缀),如 600519
    - HK:5 位,如 00700
    - US:大写 ticker 原样,如 AAPL
    """
    s = str(symbol).strip().upper()
    for prefix in ("SH", "SZ", "BJ"):
        if s.startswith(prefix):
            s = s[len(prefix):]
    m = (market or "A").upper()
    if m == "HK":
        return s.zfill(5) if s.isdigit() else s
    if m == "US":
        return s
    return s.zfill(6) if s.isdigit() else s


def with_exchange_prefix(symbol: str) -> str:
    """为东财(EM)A 股报表接口补交易所前缀:SH/SZ/BJ + 6 位代码。"""
    s = normalize_symbol(symbol, "A")
    if not s.isdigit():
        return s
    head = s[0]
    if head in ("6", "9", "5"):
        return "SH" + s
    if head in ("0", "2", "3"):
        return "SZ" + s
    if head in ("4", "8"):
        return "BJ" + s
    return "SH" + s


# 东财美股市场号:.O=纳斯达克 .N=纽交所 .A=美交所
_US_MARKET_NUM = {"O": "105", "N": "106", "A": "107"}


def us_hist_code(secucode: str) -> str:
    """由美股 SECUCODE 推导行情接口代码:'AAPL.O' -> '105.AAPL'。"""
    if not secucode or "." not in str(secucode):
        return ""
    ticker, suffix = str(secucode).split(".", 1)
    return "%s.%s" % (_US_MARKET_NUM.get(suffix.upper(), "105"), ticker)
