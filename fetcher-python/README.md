# fetcher-python — 数据抓取端

基于 [AKShare](https://akshare.akfamily.xyz/) 抓取 A 股基本面(资产负债表 / 利润表 /
现金流量表)与历史行情,按 [`../data/README.md`](../data/README.md) 约定的布局写入本地文件。

## 安装

```bash
cd fetcher-python
python3 -m venv .venv
source .venv/bin/activate
pip install -e .          # 或: pip install -r requirements.txt
```

> 注:AKShare 依赖较多(pandas/lxml 等),首次安装较慢。

## 使用

```bash
# 抓取 config.yaml 中股票池的报表 + 行情
agibuffett fetch
#   等价于  python -m agibuffett fetch

# 指定代码(A股6位,不带交易所前缀)
agibuffett fetch 600519 000001

# 港股 / 美股(用 -m 指定市场)
agibuffett fetch 00700 -m HK          # 腾讯
agibuffett fetch AAPL -m US           # 苹果
agibuffett fetch --group watch        # 按清单各自市场(A/HK/US)批量抓

# 只抓基本面 / 只抓行情
agibuffett fetch 600519 --only fundamental
agibuffett fetch 600519 --only market --adjust hfq

# 全量重抓(默认是增量)
agibuffett fetch 600519 --full

# 列出本地已抓取的股票
agibuffett list

# 自定义配置 / 调试日志
agibuffett -v -c /path/to/config.yaml fetch 600519
```

## 增量更新(默认)

每次 `fetch` 默认增量追加,不重复全量:

- **报表(JSON)**:数据源只能返回全部历史,故按 **报告期合并** —— 已有季度保留、新季度追加、同一季度若被重述则刷新;输出形如 `报表x3(新增1期)`。
- **行情(CSV)**:从本地 `daily.csv` 的最后一个交易日 **续抓**,追加去重(同日修正以新值为准),真正减少下载;输出形如 `行情480行(新增2)`。

加 `--full` 可忽略本地数据、整段重抓。

## 配置 `config.yaml`

| 字段 | 说明 |
| --- | --- |
| `data_dir` | 数据根目录,相对路径按 config.yaml 所在目录解析,默认 `../data` |
| `market.period` | `daily` / `weekly` / `monthly` |
| `market.adjust` | 复权:`qfq` 前复权 / `hfq` 后复权 / `""` 不复权 |
| `market.start_date` / `end_date` | `YYYYMMDD`;`end_date` 留空抓到今天 |
| `rate_limit.min_interval` | 请求最小间隔(秒),防止被数据源限流;批量抓建议调大,`0` 关闭 |
| `rate_limit.jitter` | 叠加的随机抖动上限(秒),让请求不那么规律 |
| `symbols` | 默认股票池 |

> **限流**:每次对东财的请求之间至少间隔 `min_interval` 秒并叠加随机抖动,
> 配合自动重试退避,降低被封禁概率。命令行可临时覆盖:`agibuffett fetch ... --min-interval 3`。

## 模块结构

```
src/agibuffett/
├── cli.py            # 命令行入口 (fetch / list)
├── config.py         # 读取 config.yaml
├── storage.py        # 写 JSON(报表)/ CSV(行情),存储契约的写入端
└── fetchers/
    ├── base.py        # 代码归一化 / 交易所前缀
    ├── fundamental.py # 三张报表 (stock_*_by_report_em)
    └── market.py      # 历史行情 (stock_zh_a_hist)
```

## 数据源说明

| 数据 | AKShare 函数 |
| --- | --- |
| A股 三表 | `stock_balance_sheet_by_report_em` / `stock_profit_sheet_by_report_em` / `stock_cash_flow_sheet_by_report_em` |
| A股 日线 / 分红 | `stock_zh_a_hist` / `stock_fhps_detail_em` |
| 港股 三表 / 日线 | `stock_financial_hk_report_em` / `stock_hk_hist` |
| 美股 三表 / 日线 | `stock_financial_us_report_em`(综合损益表)/ `stock_us_hist`(东财代码如 `105.AAPL`) |

数据源接口可能随上游变化;若字段或函数名调整,改动集中在 `fetchers/` 内。
