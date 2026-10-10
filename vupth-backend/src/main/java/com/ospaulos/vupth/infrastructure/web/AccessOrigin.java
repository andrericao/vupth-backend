package com.ospaulos.vupth.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * O IP e a porta de quem chamou, com os headers da plataforma (registro-de-acesso). O IP usa o mesmo
 * header do rate limit ({@code vupth.auth.rate-limit.ip-header}); a porta, o
 * {@code vupth.access-log.port-header} (vazio = sem porta atras do proxy).
 *
 * <p>porta-de-origem (10/10): com {@code vupth.access-log.log-headers} ligado, cada login/refresh loga os
 * NOMES dos headers que chegaram e o valor so dos que forem numero (candidatos a porta) — nenhum IP,
 * token ou cookie. E por esse log que se acha o header que o Render e o Railway repassam com a porta.
 */
@Slf4j
@Component
public class AccessOrigin {

    private static final List<String> NOT_A_PORT = List.of("content-length", "x-forwarded-port");

    private final String ipHeader;
    private final String portHeader;
    private final boolean logHeaders;

    public AccessOrigin(@Value("${vupth.auth.rate-limit.ip-header:}") String ipHeader,
                        @Value("${vupth.access-log.port-header:}") String portHeader,
                        @Value("${vupth.access-log.log-headers:false}") boolean logHeaders) {
        this.ipHeader = ipHeader;
        this.portHeader = portHeader;
        this.logHeaders = logHeaders;
    }

    public String ip(HttpServletRequest request) {
        return ClientAddress.ip(request, ipHeader);
    }

    public Integer port(HttpServletRequest request) {
        Integer port = ClientAddress.port(request, ipHeader, portHeader);
        if (logHeaders) {
            log.info("[ACCESS-HEADERS] porta gravada={} conexao={} numericos={} headers={}",
                    port, request.getRemotePort(), numericHeaders(request), headerNames(request));
        }
        return port;
    }

    static List<String> headerNames(HttpServletRequest request) {
        List<String> names = new ArrayList<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            names.add(name.toLowerCase());
        }
        Collections.sort(names);
        return names;
    }

    /** Os headers cujo valor e so um numero de 1 a 65535 (fora os que nunca sao a porta do cliente). */
    static Map<String, String> numericHeaders(HttpServletRequest request) {
        Map<String, String> numeric = new TreeMap<>();
        for (String name : headerNames(request)) {
            String value = request.getHeader(name);
            if (!NOT_A_PORT.contains(name) && value != null && value.trim().matches("\\d{1,5}")) {
                int n = Integer.parseInt(value.trim());
                if (n > 0 && n <= 65535) {
                    numeric.put(name, value.trim());
                }
            }
        }
        return numeric;
    }
}
