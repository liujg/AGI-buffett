package com.agibuffett.common.storage;

import com.agibuffett.common.model.DailyBar;
import com.agibuffett.common.model.FinancialStatement;
import com.agibuffett.common.model.PeriodRecord;
import com.agibuffett.common.model.StockMeta;
import com.agibuffett.common.model.WatchGroup;
import com.agibuffett.common.model.Watchlist;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 验证 Java 读取端能正确解析 Python 写入端约定的 JSON / CSV。 */
public class LocalStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LocalStore store;

    @Before
    public void setUp() throws IOException {
        Path data = tmp.getRoot().toPath();
        Path fund = data.resolve("fundamental").resolve("600519");
        Path market = data.resolve("market").resolve("600519");
        Files.createDirectories(fund);
        Files.createDirectories(market);

        write(fund.resolve("balance_sheet.json"),
                "{\n" +
                "  \"symbol\": \"600519\",\n" +
                "  \"statement\": \"balance_sheet\",\n" +
                "  \"source\": \"akshare:stock_balance_sheet_by_report_em\",\n" +
                "  \"fetched_at\": \"2026-06-07T12:00:00\",\n" +
                "  \"records\": [\n" +
                "    {\"report_date\": \"20251231\", \"items\": {\"MONETARYFUNDS\": 2.0E11, \"ACCOUNTS_RECE\": null}},\n" +
                "    {\"report_date\": \"20241231\", \"items\": {\"MONETARYFUNDS\": 1.5E11, \"ACCOUNTS_RECE\": 1.0E8}}\n" +
                "  ]\n" +
                "}\n");

        write(fund.resolve("meta.json"),
                "{\n" +
                "  \"symbol\": \"600519\",\n" +
                "  \"name\": \"贵州茅台\",\n" +
                "  \"source\": \"akshare\",\n" +
                "  \"fetched_at\": \"2026-06-07T12:00:00\",\n" +
                "  \"statements\": [\"balance_sheet\", \"income_statement\", \"cash_flow\"],\n" +
                "  \"market\": {\"daily_rows\": 2, \"start\": \"2024-01-02\", \"end\": \"2024-01-03\"}\n" +
                "}\n");

        write(market.resolve("daily.csv"),
                "date,open,close,high,low,volume,amount,amplitude,pct_change,change,turnover\n" +
                "2024-01-02,1608.68,1578.69,1611.87,1571.78,32156,5440000000.0,2.48,-2.53,-40.99,0.26\n" +
                "2024-01-03,1574.79,1587.68,1588.9,1570.01,20229,3410000000.0,1.2,0.57,8.99,0.16\n");

        write(data.resolve("watchlist.json"),
                "{\n" +
                "  \"version\": 1,\n" +
                "  \"updated_at\": \"2026-06-08\",\n" +
                "  \"groups\": [\n" +
                "    {\"key\": \"watch\", \"name\": \"自选\", \"items\": [\n" +
                "      {\"symbol\": \"600519\", \"market\": \"A\", \"name\": \"贵州茅台\"},\n" +
                "      {\"symbol\": \"00700\", \"market\": \"HK\", \"name\": \"腾讯控股\"}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}\n");

        store = new LocalStore(data);
    }

    @Test
    public void readsStatementWithNullsAndOrdering() {
        FinancialStatement bs = store.readBalanceSheet("600519");
        assertEquals("600519", bs.getSymbol());
        assertEquals(2, bs.getRecords().size());

        PeriodRecord latest = bs.latest();
        assertEquals("20251231", latest.getReportDate());
        assertEquals(2.0E11, latest.get("MONETARYFUNDS"), 1.0);
        assertNull("缺失值应解析为 null", latest.get("ACCOUNTS_RECE"));

        PeriodRecord prior = bs.recordOf("20241231");
        assertEquals(1.0E8, prior.get("ACCOUNTS_RECE"), 1.0);
    }

    @Test
    public void readsMeta() {
        StockMeta meta = store.readMeta("600519");
        assertEquals("贵州茅台", meta.getName());
        assertEquals(3, meta.getStatements().size());
        assertEquals(2, meta.getMarket().getDailyRows());
        assertEquals("2024-01-03", meta.getMarket().getEnd());
    }

    @Test
    public void readsDailyBarsAscending() {
        List<DailyBar> bars = store.readDaily("600519");
        assertEquals(2, bars.size());
        assertEquals("2024-01-02", bars.get(0).getDate().toString());
        assertEquals(1578.69, bars.get(0).getClose(), 1e-6);
        assertEquals(1587.68, bars.get(1).getClose(), 1e-6);
        assertTrue(bars.get(0).getDate().isBefore(bars.get(1).getDate()));
    }

    @Test
    public void readsWatchlistWithMarkets() {
        Watchlist wl = store.readWatchlist();
        assertEquals(1, wl.getGroups().size());
        WatchGroup watch = wl.group("watch");
        assertEquals("自选", watch.getName());
        assertEquals(2, watch.getItems().size());
        assertEquals(1, watch.itemsOf("A").size());
        assertEquals(1, watch.itemsOf("HK").size());
        assertEquals("腾讯控股", watch.itemsOf("HK").get(0).getName());
    }

    @Test
    public void returnsEmptyWatchlistWhenMissing() {
        LocalStore empty = new LocalStore(tmp.getRoot().toPath().resolve("nope"));
        assertTrue(empty.readWatchlist().getGroups().isEmpty());
    }

    @Test
    public void listsSymbols() {
        assertEquals(1, store.listSymbols().size());
        assertEquals("600519", store.listSymbols().get(0));
    }

    private static void write(Path p, String content) throws IOException {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }
}
