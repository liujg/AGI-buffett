# 本地数据存储布局 (Storage Contract)

> 这是 **Python 抓取端(写)** 与 **Java 分析端(读)** 之间的契约。
> 改动这里的目录结构 / 文件格式时,两端必须同步更新:
> - 写:`fetcher-python/src/agibuffett/storage.py`
> - 读:`buffett-common/src/main/java/com/agibuffett/common/storage/LocalStore.java`

## 目录结构

```
data/
├── watchlist.json               # 自选股分组(人工维护,纳入版本管理)
├── fundamental/                 # 基本面 (JSON)
│   └── <symbol>/                # 例如 600519
│       ├── meta.json            # 股票元信息 + 抓取时间
│       ├── balance_sheet.json   # 资产负债表
│       ├── income_statement.json# 利润表
│       ├── cash_flow.json       # 现金流量表
│       └── dividend.json        # 分红送配(现金/送转/股息率/除权除息日)
└── market/                      # 行情 (CSV)
    └── <symbol>/
        ├── daily.csv            # 日线·前复权(主),按日期升序
        └── daily_hfq.csv        # 日线·后复权(供前端切换)
```

## 分红 `dividend.json`

与报表同构(`records` 按报告期降序),但 `items` 改为扁平字段:

```json
{
  "symbol": "600519", "statement": "dividend",
  "source": "akshare:stock_fhps_detail_em", "fetched_at": "...",
  "records": [
    { "report_date": "20251231", "cash_per_10": 280.24, "dividend_yield": 0.022,
      "ex_date": null, "record_date": null, "plan": "董事会决议通过",
      "plan_desc": "10派280.24元(含税)", "eps": 65.66, "bps": 195.36 }
  ]
}
```

- `cash_per_10` 现金分红(元/10 股);`dividend_yield` 股息率(小数);`bonus_per_10`/`transfer_per_10` 送/转股。
- 行情 `daily_hfq.csv` 与 `daily.csv` 表头、排序一致,仅复权方式不同(后复权由 AKShare `adjust=hfq` 提供)。

## 自选股清单 `watchlist.json`

人工维护的关注列表,被抓取端(决定抓哪些)与分析/展示端共用。可手改,也可用
`agibuffett watch add/rm/list` 维护。**纳入版本管理**(非抓取生成的数据)。

```json
{
  "version": 1,
  "updated_at": "2026-06-08",
  "groups": [
    {
      "key": "watch",            // 分组标识(英文,稳定不变)
      "name": "自选",            // 分组显示名
      "items": [
        { "symbol": "600519", "market": "A",  "name": "贵州茅台" },
        { "symbol": "00700",  "market": "HK", "name": "腾讯控股" }
      ]
    }
  ]
}
```

- 内置分组:`watch` 自选 / `focus` 重点观察 / `opportunity` 机会点(可自行增删)。
- `market` 取值:`A`(沪深 A 股)、`HK`(港股)、`US`(美股),三者均支持抓取。
- `symbol`:A 股 6 位代码(如 `600519`)、港股 5 位(如 `00700`)、美股代码(如 `AAPL`)。
- 同一标的可同时出现在多个分组。
- 港股/美股报表为东财长表透视而来,科目名为中文(如「总资产」「营业额」「营业收入」);
  美股仅年报、财年末非 12-31。
- 分红(`dividend.json`):A 股每 10 股口径含股息率;港股每股派息(`cash_per_share`,原币种);美股暂无数据源。

`<symbol>` 统一使用 **6 位代码**(不带交易所前缀),如 `600519`、`000001`。

## 报表 JSON 格式 (`*.json`)

```json
{
  "symbol": "600519",
  "statement": "balance_sheet",
  "source": "akshare:stock_balance_sheet_by_report_em",
  "fetched_at": "2026-06-07T12:00:00",
  "records": [
    {
      "report_date": "20231231",
      "items": { "货币资金": 123456789.0, "应收账款": 0.0 }
    },
    {
      "report_date": "20221231",
      "items": { "货币资金": 98765432.0 }
    }
  ]
}
```

- `records` 按报告期 **降序**(最新在前)。
- `items` 的 key 为报表行项目名(来自数据源原始列名),value 为数值或 `null`。
- 金额单位以数据源为准(东财接口一般为 **元**)。

## meta.json 格式

```json
{
  "symbol": "600519",
  "name": "贵州茅台",
  "source": "akshare",
  "fetched_at": "2026-06-07T12:00:00",
  "statements": ["balance_sheet", "income_statement", "cash_flow"],
  "market": { "daily_rows": 480, "start": "2024-01-02", "end": "2026-06-06" }
}
```

## 行情 CSV 格式 (`daily.csv`)

表头固定(列来自 AKShare `stock_zh_a_hist`,已归一化为英文):

```
date,open,close,high,low,volume,amount,amplitude,pct_change,change,turnover
2024-01-02,1685.0,1700.5,...
```

- `date` 为 `YYYY-MM-DD`,按 **升序**。
- 数值列为浮点;`volume` 为成交量(手),`amount` 为成交额(元)。
