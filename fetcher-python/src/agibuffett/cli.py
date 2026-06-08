"""命令行入口。

用法:
    python -m agibuffett fetch                 # 抓 config.yaml 中的股票池(报表+行情)
    python -m agibuffett fetch 600519 000001   # 指定代码
    python -m agibuffett fetch --group watch    # 抓自选分组(按各自市场 A/HK/US)
    python -m agibuffett fetch 00700 -m HK      # 港股(腾讯)
    python -m agibuffett fetch AAPL -m US       # 美股(苹果)
    python -m agibuffett fetch 600519 --only fundamental
    python -m agibuffett fetch 600519 --only market --adjust hfq
    python -m agibuffett list                  # 列出已抓取到本地的股票
    python -m agibuffett watch list             # 查看自选股分组
    python -m agibuffett watch add 00700 -g watch -m HK -n 腾讯控股
    python -m agibuffett watch rm 00700 -g watch
"""
from __future__ import annotations

import argparse
import logging
import sys
from typing import List

from agibuffett.config import load_config
from agibuffett.fetchers import DividendFetcher, FundamentalFetcher, MarketFetcher
from agibuffett.fetchers.base import configure_rate_limiter
from agibuffett.storage import LocalStore
from agibuffett.watchlist import VALID_MARKETS, WatchlistStore


def _setup_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    # 屏蔽 akshare 进度条之外的噪声
    logging.getLogger("urllib3").setLevel(logging.WARNING)


def cmd_fetch(args: argparse.Namespace) -> int:
    cfg = load_config(args.config)
    if args.adjust is not None:
        cfg.market.adjust = args.adjust
    if args.min_interval is not None:
        cfg.rate_limit.min_interval = args.min_interval
    configure_rate_limiter(cfg.rate_limit.min_interval, cfg.rate_limit.jitter)
    store = LocalStore(cfg.data_dir)
    log = logging.getLogger(__name__)

    # 构建抓取目标:(代码, 市场)。--group 用清单内各自市场;否则用 --market(默认 A)。
    targets: List[tuple] = []
    if args.group:
        items = WatchlistStore(cfg.data_dir).items(key=args.group)
        if not items:
            print(f"分组 '{args.group}' 为空或不存在(见 watch list)。", file=sys.stderr)
            return 2
        targets = [(it["symbol"], (it.get("market") or "A").upper()) for it in items]
    elif args.symbols:
        mkt = (args.market or "A").upper()
        targets = [(s, mkt) for s in args.symbols]
    else:
        targets = [(s, "A") for s in cfg.symbols]
    # 去重保序
    seen = set()
    targets = [t for t in targets if not (t in seen or seen.add(t))]
    if not targets:
        print("没有要抓取的股票:传入代码、用 --group,或在 config.yaml 配置 symbols。", file=sys.stderr)
        return 2

    do_fund = args.only in (None, "fundamental")
    do_market = args.only in (None, "market")

    fund = FundamentalFetcher(store)
    market = MarketFetcher(store, cfg.market)
    dividend = DividendFetcher(store)

    log.info("数据目录: %s | 目标: %s | 报表=%s 行情=%s",
             store.data_dir, ",".join("%s(%s)" % t for t in targets), do_fund, do_market)

    failures = []
    for sym, mkt in targets:
        # 在已有 meta 基础上合并:--only 单独抓某类时,不会抹掉另一类的元信息(如名称)。
        meta = store.read_meta(sym) or {}
        meta["symbol"] = sym
        meta["market_type"] = mkt
        # 报表与行情各自独立抓取:任一部分失败都不影响另一部分。
        fund_res = None
        mkt_res = None
        div_res = None
        if do_fund:
            try:
                fund_res = fund.fetch_symbol(sym, market=mkt, full=args.full)
                meta.update(fund_res)
            except Exception as e:  # noqa: BLE001
                log.exception("[%s] 报表抓取失败", sym)
                failures.append((sym + " 报表", str(e)))
            # 分红:A 股(每10股)/ 港股(每股);美股无源,内部跳过
            if mkt in ("A", "HK"):
                try:
                    div_res = dividend.fetch_symbol(sym, market=mkt, full=args.full)
                    meta["dividend"] = div_res
                except Exception as e:  # noqa: BLE001
                    log.exception("[%s] 分红抓取失败", sym)
                    failures.append((sym + " 分红", str(e)))
        if do_market:
            try:
                us_code = (fund_res or {}).get("us_code") or meta.get("us_code")
                mkt_res = market.fetch_symbol(sym, market=mkt, full=args.full, us_code=us_code)
                meta["market"] = mkt_res
            except Exception as e:  # noqa: BLE001
                log.exception("[%s] 行情抓取失败", sym)
                failures.append((sym + " 行情", str(e)))

        store.write_meta(sym, meta)
        got = []  # 摘要只反映本次抓取的动作
        if fund_res:
            new_p = sum((fund_res.get("added_periods") or {}).values())
            got.append("报表x%d(新增%d期)" % (len(fund_res.get("statements", [])), new_p))
        if isinstance(div_res, dict) and div_res.get("dividend_rows"):
            got.append("分红%d期(新增%d)" % (div_res["dividend_rows"], div_res.get("dividend_added", 0)))
        if isinstance(mkt_res, dict) and mkt_res.get("daily_rows"):
            hfq = "+hfq" if mkt_res.get("hfq") else ""
            got.append("行情%d行(新增%d)%s" % (mkt_res["daily_rows"], mkt_res.get("added", 0), hfq))
        print(f"✓ {sym}({mkt}) {meta.get('name', '')} [{', '.join(got) or '无新数据'}]")

    if failures:
        print(f"\n{len(failures)} 只失败:", file=sys.stderr)
        for sym, err in failures:
            print(f"  ✗ {sym}: {err}", file=sys.stderr)
        return 1
    return 0


def cmd_list(args: argparse.Namespace) -> int:
    cfg = load_config(args.config)
    store = LocalStore(cfg.data_dir)
    fdir = store.fundamental_dir
    if not fdir.exists():
        print("(尚无数据)")
        return 0
    for d in sorted(p for p in fdir.iterdir() if p.is_dir()):
        files = sorted(f.name for f in d.glob("*.json"))
        has_mkt = (store.symbol_market_dir(d.name) / "daily.csv").exists()
        print(f"{d.name}: {', '.join(files)}{'  +daily.csv' if has_mkt else ''}")
    return 0


def cmd_watch(args: argparse.Namespace) -> int:
    cfg = load_config(args.config)
    ws = WatchlistStore(cfg.data_dir)

    if args.watch_action == "list":
        wl = ws.load()
        groups = wl.get("groups", [])
        if not groups:
            print("(自选股清单为空)")
            return 0
        for g in groups:
            items = g.get("items", [])
            print(f"[{g.get('key')}] {g.get('name', '')} — {len(items)} 只")
            for it in items:
                print(f"    {it.get('market', 'A'):<2} {it.get('symbol', ''):<8} {it.get('name', '')}")
        return 0

    if args.watch_action == "add":
        status = ws.add(args.symbol, group=args.group, market=args.market, name=args.name or "")
        tip = "已添加" if status == "added" else "已存在(未重复)"
        print(f"{tip}:{args.symbol} [{args.market}] -> 分组 {args.group}")
        return 0

    if args.watch_action == "rm":
        n = ws.remove(args.symbol, group=args.group)
        scope = f"分组 {args.group}" if args.group else "全部分组"
        print(f"已从{scope}移除 {n} 条:{args.symbol}")
        return 0 if n else 1

    return 2


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="agibuffett", description="AGI-Buffett 数据抓取")
    p.add_argument("-c", "--config", help="配置文件路径(默认 fetcher-python/config.yaml)")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="command", required=True)

    pf = sub.add_parser("fetch", help="抓取报表与行情")
    pf.add_argument("symbols", nargs="*", help="股票代码:A股6位/港股5位/美股ticker;留空用 --group 或 config")
    pf.add_argument("-m", "--market", default="A", choices=["A", "HK", "US"],
                    help="positional 代码所属市场(默认 A);--group 时按清单各自市场")
    pf.add_argument("-g", "--group", help="抓取自选股清单中某分组(watch/focus/opportunity)的全部标的")
    pf.add_argument("--only", choices=["fundamental", "market"], help="只抓其中一类")
    pf.add_argument("--adjust", choices=["qfq", "hfq", ""], help="覆盖行情复权方式")
    pf.add_argument("--min-interval", type=float, default=None,
                    help="覆盖请求最小间隔(秒);抓得越多建议越大,默认见 config.yaml")
    pf.add_argument("--full", action="store_true",
                    help="全量重抓(默认增量:报表按报告期合并、行情从最后日期续抓)")
    pf.set_defaults(func=cmd_fetch)

    pl = sub.add_parser("list", help="列出本地已抓取的股票")
    pl.set_defaults(func=cmd_list)

    # watch: 维护自选股清单
    pw = sub.add_parser("watch", help="维护自选股清单(分组 / 多市场)")
    wsub = pw.add_subparsers(dest="watch_action", required=True)

    pw_list = wsub.add_parser("list", help="查看分组与成员")
    pw_list.set_defaults(func=cmd_watch)

    pw_add = wsub.add_parser("add", help="添加一个标的到分组")
    pw_add.add_argument("symbol", help="代码:A股6位 / 港股5位 / 美股代码")
    pw_add.add_argument("-g", "--group", required=True, help="分组 key,如 watch/focus/opportunity")
    pw_add.add_argument("-m", "--market", default="A", choices=list(VALID_MARKETS),
                        help="市场:A(沪深)/HK(港股)/US(美股),默认 A")
    pw_add.add_argument("-n", "--name", default="", help="名称(可选)")
    pw_add.set_defaults(func=cmd_watch)

    pw_rm = wsub.add_parser("rm", help="从分组(或全部)移除一个标的")
    pw_rm.add_argument("symbol")
    pw_rm.add_argument("-g", "--group", default=None, help="限定分组;省略则从所有分组移除")
    pw_rm.set_defaults(func=cmd_watch)
    return p


def main(argv: List[str] = None) -> int:
    args = build_parser().parse_args(argv)
    _setup_logging(getattr(args, "verbose", False))
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
