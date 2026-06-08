package com.agibuffett.common.storage;

import com.agibuffett.common.model.DailyBar;
import com.agibuffett.common.model.DividendData;
import com.agibuffett.common.model.FinancialStatement;
import com.agibuffett.common.model.StockMeta;
import com.agibuffett.common.model.Watchlist;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地文件存储的 <b>读取端</b>,与 Python 写入端
 * ({@code fetcher-python/.../storage.py}) 共享 {@code data/README.md} 的契约。
 */
public class LocalStore {

    /** 报表名常量(对应文件名与 JSON 中的 statement 字段)。 */
    public static final String BALANCE_SHEET = "balance_sheet";
    public static final String INCOME_STATEMENT = "income_statement";
    public static final String CASH_FLOW = "cash_flow";
    public static final String DIVIDEND = "dividend";
    /** 行情文件名:前复权(主)/ 后复权 */
    public static final String DAILY = "daily.csv";
    public static final String DAILY_HFQ = "daily_hfq.csv";

    private final Path dataDir;
    private final ObjectMapper mapper;

    public LocalStore(Path dataDir) {
        this.dataDir = dataDir;
        this.mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public LocalStore(String dataDir) {
        this(Paths.get(dataDir));
    }

    public Path getDataDir() {
        return dataDir;
    }

    // ---- 路径布局(必须与 Python 端一致) ----
    public Path fundamentalDir() {
        return dataDir.resolve("fundamental");
    }

    public Path marketDir() {
        return dataDir.resolve("market");
    }

    public Path statementFile(String symbol, String statement) {
        return fundamentalDir().resolve(symbol).resolve(statement + ".json");
    }

    public Path metaFile(String symbol) {
        return fundamentalDir().resolve(symbol).resolve("meta.json");
    }

    public Path dailyFile(String symbol) {
        return dailyFile(symbol, DAILY);
    }

    public Path dailyFile(String symbol, String filename) {
        return marketDir().resolve(symbol).resolve(filename);
    }

    public Path dividendFile(String symbol) {
        return fundamentalDir().resolve(symbol).resolve("dividend.json");
    }

    public Path watchlistFile() {
        return dataDir.resolve("watchlist.json");
    }

    // ---- 读自选股清单 ----
    /** 读取 {@code data/watchlist.json};文件不存在时返回空清单(非 null)。 */
    public Watchlist readWatchlist() {
        Path p = watchlistFile();
        if (!Files.exists(p)) {
            return new Watchlist();
        }
        try {
            return mapper.readValue(p.toFile(), Watchlist.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取自选股清单失败: " + p, e);
        }
    }

    // ---- 读报表 ----
    public FinancialStatement readStatement(String symbol, String statement) {
        Path p = statementFile(symbol, statement);
        try {
            return mapper.readValue(p.toFile(), FinancialStatement.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取报表失败: " + p, e);
        }
    }

    public FinancialStatement readBalanceSheet(String symbol) {
        return readStatement(symbol, BALANCE_SHEET);
    }

    public FinancialStatement readIncomeStatement(String symbol) {
        return readStatement(symbol, INCOME_STATEMENT);
    }

    public FinancialStatement readCashFlow(String symbol) {
        return readStatement(symbol, CASH_FLOW);
    }

    public StockMeta readMeta(String symbol) {
        Path p = metaFile(symbol);
        try {
            return mapper.readValue(p.toFile(), StockMeta.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 meta 失败: " + p, e);
        }
    }

    public DividendData readDividends(String symbol) {
        Path p = dividendFile(symbol);
        if (!Files.exists(p)) {
            return new DividendData();
        }
        try {
            return mapper.readValue(p.toFile(), DividendData.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取分红失败: " + p, e);
        }
    }

    // ---- 读行情 ----
    /** 读取前复权日线(daily.csv)。 */
    public List<DailyBar> readDaily(String symbol) {
        return readDaily(symbol, DAILY);
    }

    /** 读取指定复权序列(daily.csv / daily_hfq.csv);按日期升序。不存在返回空列表。 */
    public List<DailyBar> readDaily(String symbol, String filename) {
        Path p = dailyFile(symbol, filename);
        if (!Files.exists(p)) {
            return Collections.emptyList();
        }
        List<DailyBar> bars = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build();
        try (Reader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(reader, format)) {
            for (CSVRecord r : parser) {
                DailyBar b = new DailyBar();
                b.setDate(LocalDate.parse(r.get("date")));
                b.setOpen(parseDouble(r, "open"));
                b.setClose(parseDouble(r, "close"));
                b.setHigh(parseDouble(r, "high"));
                b.setLow(parseDouble(r, "low"));
                b.setVolume(parseDouble(r, "volume"));
                b.setAmount(parseDouble(r, "amount"));
                b.setAmplitude(parseDouble(r, "amplitude"));
                b.setPctChange(parseDouble(r, "pct_change"));
                b.setChange(parseDouble(r, "change"));
                b.setTurnover(parseDouble(r, "turnover"));
                bars.add(b);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取行情失败: " + p, e);
        }
        return bars;
    }

    // ---- 列出本地已抓取的股票 ----
    public List<String> listSymbols() {
        Path fdir = fundamentalDir();
        List<String> symbols = new ArrayList<>();
        if (!Files.isDirectory(fdir)) {
            return symbols;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(fdir)) {
            for (Path d : ds) {
                if (Files.isDirectory(d)) {
                    symbols.add(d.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("列出股票失败: " + fdir, e);
        }
        Collections.sort(symbols);
        return symbols;
    }

    private static double parseDouble(CSVRecord r, String col) {
        if (!r.isMapped(col)) {
            return Double.NaN;
        }
        String v = r.get(col);
        if (v == null || v.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
