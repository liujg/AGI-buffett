package com.agibuffett.web;

import com.agibuffett.common.storage.LocalStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 本地量化终端 Web 服务(JDK 内置 HttpServer,零额外重框架)。
 *
 * <pre>
 *   mvn -q -pl buffett-web -am exec:java            # 默认端口 8080,数据目录自动探测
 *   mvn -q -pl buffett-web exec:java -Dexec.args="8090 /path/to/data"
 * </pre>
 *
 * 提供:静态页面(classpath 下 web/)+ REST:
 *   GET /api/watchlist          自选股分组
 *   GET /api/stock?symbol=600519 个股基本面详情
 */
public final class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebServer() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : envInt("PORT", 8080);
        Path dataDir = resolveDataDir(args.length > 1 ? args[1] : null);
        StockService service = new StockService(new LocalStore(dataDir));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/api/watchlist", new ApiHandler(ex -> service.watchlist()));
        // 写自选:添加 / 删除(POST JSON),同步落盘 watchlist.json
        server.createContext("/api/watchlist/add", new ApiHandler(ex -> {
            Map<String, Object> b = body(ex);
            return service.addWatch(str(b, "symbol"), str(b, "market"), str(b, "name"), str(b, "group"));
        }));
        server.createContext("/api/watchlist/remove", new ApiHandler(ex -> {
            Map<String, Object> b = body(ex);
            return service.removeWatch(str(b, "symbol"), str(b, "group"));
        }));
        server.createContext("/api/stock", new ApiHandler(ex -> {
            Map<String, String> q = query(ex.getRequestURI());
            String symbol = q.get("symbol");
            if (symbol == null || symbol.isEmpty()) {
                throw new IllegalArgumentException("缺少 symbol 参数");
            }
            return service.detail(symbol, q.getOrDefault("market", "A"));
        }));
        server.createContext("/", new StaticHandler());

        server.start();
        log.info("数据目录: {}", dataDir.toAbsolutePath());
        log.info("AGI-Buffett 终端已启动 ->  http://localhost:{}", port);
    }

    // ===== REST 处理器 =====
    @FunctionalInterface
    interface ApiLogic {
        Object handle(HttpExchange ex) throws Exception;
    }

    static final class ApiHandler implements HttpHandler {
        private final ApiLogic logic;

        ApiHandler(ApiLogic logic) {
            this.logic = logic;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                Object body = logic.handle(ex);
                writeJson(ex, 200, body);
            } catch (IllegalArgumentException e) {
                writeJson(ex, 400, error(e.getMessage()));
            } catch (Exception e) {
                log.warn("处理 {} 失败", ex.getRequestURI(), e);
                writeJson(ex, 500, error(e.toString()));
            } finally {
                ex.close();
            }
        }
    }

    // ===== 静态资源(classpath: web/)=====
    static final class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path == null || "/".equals(path)) {
                path = "/index.html";
            }
            if (path.contains("..")) {
                ex.sendResponseHeaders(403, -1);
                ex.close();
                return;
            }
            String resource = "web" + path;
            try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    byte[] nf = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(404, nf.length);
                    ex.getResponseBody().write(nf);
                    ex.close();
                    return;
                }
                byte[] bytes = readAll(in);
                ex.getResponseHeaders().set("Content-Type", contentType(path));
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
            } finally {
                ex.close();
            }
        }
    }

    // ===== 工具 =====
    private static void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    /** 读取并解析请求体 JSON 为 Map(空体返回空 Map)。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] data = readAll(ex.getRequestBody());
        if (data.length == 0) {
            return new HashMap<>();
        }
        return MAPPER.readValue(data, Map.class);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> map = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(urlDecode(pair.substring(0, i)), urlDecode(pair.substring(i + 1)));
            }
        }
        return map;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    /** 探测数据目录:显式参数 > ./data > ../data。 */
    private static Path resolveDataDir(String arg) {
        if (arg != null && !arg.isEmpty()) {
            return Paths.get(arg);
        }
        for (String c : new String[]{"data", "../data"}) {
            Path p = Paths.get(c);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return Paths.get("data");
    }

    private static int envInt(String key, int def) {
        String v = System.getenv(key);
        try {
            return v == null ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
