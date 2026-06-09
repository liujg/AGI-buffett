# AGI-Buffett

价值投资分析框架。三层结构,以本地文件为中间存储解耦:

```
┌─────────────────┐     data/(JSON+CSV)     ┌──────────────────┐     ┌──────────────┐
│  数据抓取        │ ──────────────────────▶ │  数据分析 (Java)  │ ──▶ │  前端展示     │
│  Python (AKShare)│                         │  指标 / 规则      │     │  (待定)       │
│  + Java(预留)   │                         │                  │     │              │
└─────────────────┘                         └──────────────────┘     └──────────────┘
```

- **数据抓取**:Python(AKShare),A股/港股/美股 基本面三张报表 + 历史行情(前/后复权)+ A股分红,内置限流。
- **存储**:本地文件,报表用 JSON、行情用 CSV。契约见 [`data/README.md`](data/README.md)。
- **数据分析**:Java(Maven 多模块)。当前为可扩展骨架,具体算法/规则**待定**。
- **前端展示**:**待定**(占位模块 `buffett-web`)。

## 目录结构

```
AGI-buffett/
├── pom.xml                 # Maven 父 POM(Java 8)
├── data/                   # 本地数据(.gitignore;由抓取端生成)
│   ├── README.md           # ★ 存储契约:Python 写 / Java 读
│   └── watchlist.json      # 自选股分组(人工维护,纳入版本管理)
├── fetcher-python/         # 数据抓取(Python + AKShare)—— 已可用
├── buffett-common/         # Java 公共:数据模型 + 本地存储读取
├── buffett-analysis/       # Java 数据分析(骨架,待定)
└── buffett-web/            # 前端展示(占位,待定)
```

## 快速开始

### 1) 抓取数据(Python)

```bash
cd fetcher-python
python3 -m venv .venv && source .venv/bin/activate
pip install -e .
agibuffett fetch 600519 000001     # 报表 + 行情写入 ../data/(默认增量)
agibuffett fetch --group watch     # 抓自选分组里的 A 股
agibuffett list                    # 查看本地已有数据

# 自选股清单(分组 + 多市场 A/HK/US,存于 data/watchlist.json)
agibuffett watch list
agibuffett watch add 00700 -g watch -m HK -n 腾讯控股
```

详见 [`fetcher-python/README.md`](fetcher-python/README.md)。

### 2) 一键启动前端(推荐)

```bash
./restart.sh            # 关闭旧实例→编译→打包→启动,默认端口 8080
./restart.sh 8090       # 指定端口
./restart.sh 8080 stop  # 仅关闭
```
启动后访问 http://localhost:8080(日志 `buffett-web.log`)。

### 3) 构建分析端(Java 8 + Maven)

```bash
mvn clean install                  # 编译 + 跑测试

# 运行示例分析(读取 data/ 中已抓取的股票)
mvn -q -pl buffett-analysis exec:java -Dexec.args="data 600519"
```

## 环境

- Java 8、Maven 3.6+
- Python 3.9+(已在 3.14 + AKShare 1.18 验证)

## 后续(待定)

- **数据分析**:基本面指标(ROE/毛利率/现金流质量/成长性)、估值、护城河打分、选股规则引擎。
  扩展点:实现 `Analyzer` / `Rule` 接口并在 `AnalysisEngine` 注册。
- **前端展示**:见 `buffett-web` 的 `package-info` 中的候选方案。
