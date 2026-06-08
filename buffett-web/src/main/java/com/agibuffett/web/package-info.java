/**
 * 前端展示模块:本地「量化终端」风格页面。
 *
 * <p>{@link com.agibuffett.web.WebServer} 用 JDK 内置 HttpServer 起一个本地服务,
 * 通过 {@link com.agibuffett.web.StockService} 读取 {@code data/}(自选股清单 + 基本面),
 * 以 REST 提供数据;静态页面在 {@code resources/web/} 下,用原生 HTML/CSS/JS 渲染
 * (深色科技风,按 watchlist 分组展示个股基本面),无前端构建步骤、可离线运行。
 */
package com.agibuffett.web;
