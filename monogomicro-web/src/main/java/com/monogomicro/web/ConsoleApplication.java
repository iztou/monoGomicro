package com.monogomicro.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 本地控制台入口。
 *
 * A-11：Host/Origin 白名单过滤（DNS rebinding 防护）——与 MCP 模块的
 * LoopbackHostValidator 同一套语义：本地无认证服务只接受回环 Host 与同源 Origin，
 * 恶意页面把域名重解析到 127.0.0.1 也无法跨站驱动写接口。
 */
@SpringBootApplication
public class ConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsoleApplication.class, args);
    }

    @Bean
    public OncePerRequestFilter hostAllowlistFilter() {
        return new OncePerRequestFilter() {
            private boolean matches(String hostHeader) {
                // host:port / host 形式，只放行回环
                String h = hostHeader == null ? "" : hostHeader.toLowerCase();
                int c = h.lastIndexOf(':');
                String bare = c > 0 ? h.substring(0, c) : h;
                return Set.of("127.0.0.1", "localhost", "[::1]", "[0:0:0:0:0:0:0:1]").contains(bare);
            }

            /** F-03：浏览器同源 Origin 带 :port（http://127.0.0.1:8080）——按主机段判定。 */
            private boolean originAllowed(String origin) {
                String o = origin.toLowerCase();
                for (String host : new String[]{"http://127.0.0.1", "http://localhost", "http://[::1]"}) {
                    if (o.equals(host) || o.startsWith(host + ":")) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (!matches(request.getHeader("Host"))) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Host not allowed");
                    return;
                }
                String origin = request.getHeader("Origin");
                if (origin != null && !origin.isBlank() && !originAllowed(origin)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
    }
}
