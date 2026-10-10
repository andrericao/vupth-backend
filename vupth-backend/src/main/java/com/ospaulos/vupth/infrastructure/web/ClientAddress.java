package com.ospaulos.vupth.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * O endereco de quem chamou, atras do proxy da plataforma (ip-forjado-no-limite, 05/10; registro-de-acesso,
 * 10/10). O IP vem do header que a borda escreve ({@code CF-Connecting-IP} no Render, o ULTIMO valor do
 * {@code X-Forwarded-For} no Railway); sem header configurado ou presente, o da conexao.
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    /** O IP de quem chamou. {@code ipHeader} vazio = o IP da conexao. */
    public static String ip(HttpServletRequest request, String ipHeader) {
        String value = fromHeader(request, ipHeader);
        if (value == null) {
            return request.getRemoteAddr();
        }
        if ("X-Forwarded-For".equalsIgnoreCase(ipHeader)) {
            String[] hops = value.split(",");
            return hops[hops.length - 1].trim();
        }
        return value.trim();
    }

    /**
     * A porta de origem (Marco Civil, art. 15: a "porta logica" separa clientes atras do mesmo IP de
     * operadora). Atras do proxy a porta da conexao e a do proxy, nao a do cliente: so vale a do header
     * {@code portHeader}, se a plataforma escrever um; senao {@code null}. Sem proxy, a da conexao.
     */
    public static Integer port(HttpServletRequest request, String ipHeader, String portHeader) {
        if (fromHeader(request, ipHeader) == null) {
            return request.getRemotePort();
        }
        String value = fromHeader(request, portHeader);
        if (value == null) {
            return null;
        }
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fromHeader(HttpServletRequest request, String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = request.getHeader(header);
        return value == null || value.isBlank() ? null : value;
    }
}
