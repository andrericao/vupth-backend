package com.ospaulos.vupth.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O IP e a porta de quem chamou, com os headers da plataforma (registro-de-acesso). O IP usa o mesmo
 * header do rate limit ({@code vupth.auth.rate-limit.ip-header}); a porta, o
 * {@code vupth.access-log.port-header} (vazio = sem porta atras do proxy).
 */
@Component
public class AccessOrigin {

    private final String ipHeader;
    private final String portHeader;

    public AccessOrigin(@Value("${vupth.auth.rate-limit.ip-header:}") String ipHeader,
                        @Value("${vupth.access-log.port-header:}") String portHeader) {
        this.ipHeader = ipHeader;
        this.portHeader = portHeader;
    }

    public String ip(HttpServletRequest request) {
        return ClientAddress.ip(request, ipHeader);
    }

    public Integer port(HttpServletRequest request) {
        return ClientAddress.port(request, ipHeader, portHeader);
    }
}
