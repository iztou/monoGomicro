package com.monogomicro.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-03/A-11 回归：本地控制台的 Host/Origin 白名单。
 *
 * F-03：浏览器同源 POST 会发带端口的 Origin（http://127.0.0.1:8080）——修复前白名单
 * 只匹配无端口前缀，控制台全部写接口 403。A-11：伪造 Host（DNS rebinding）必须 403。
 */
class HostOriginFilterTest {

    private MockMvc mvc;

    @RestController
    static class PingController {
        @GetMapping("/api/ping")
        public String ping() {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PingController())
                .addFilters(new ConsoleApplication().hostAllowlistFilter())
                .build();
    }

    @Test
    void sameOriginWithPortIsAllowed() throws Exception {
        // F-03 核心：Origin 带端口（浏览器同源形态）不得 403
        mvc.perform(get("/api/ping")
                        .header(HttpHeaders.HOST, "127.0.0.1:8080")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:8080"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ping")
                        .header(HttpHeaders.HOST, "localhost:8080")
                        .header(HttpHeaders.ORIGIN, "http://localhost:8080"))
                .andExpect(status().isOk());
    }

    @Test
    void loopbackHostWithoutOriginIsAllowed() throws Exception {
        mvc.perform(get("/api/ping").header(HttpHeaders.HOST, "127.0.0.1:8080"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ping").header(HttpHeaders.HOST, "[::1]:8080"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ping").header(HttpHeaders.HOST, "localhost"))
                .andExpect(status().isOk());
    }

    @Test
    void forgedHostIsForbidden() throws Exception {
        // A-11：DNS rebinding——域名解析到 127.0.0.1 后 Host 仍是恶意域
        mvc.perform(get("/api/ping").header(HttpHeaders.HOST, "evil.example:8080"))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossOriginIsForbidden() throws Exception {
        mvc.perform(get("/api/ping")
                        .header(HttpHeaders.HOST, "127.0.0.1:8080")
                        .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ping")
                        .header(HttpHeaders.HOST, "127.0.0.1:8080")
                        .header(HttpHeaders.ORIGIN, "http://evil.example:8080"))
                .andExpect(status().isForbidden());
    }
}
