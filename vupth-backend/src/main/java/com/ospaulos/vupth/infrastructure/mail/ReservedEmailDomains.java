package com.ospaulos.vupth.infrastructure.mail;

import java.util.Locale;
import java.util.Set;

/**
 * Dominios reservados para teste (RFC 2606 / RFC 6761): nunca existem, entao e-mail para eles nao
 * chega a ninguem. O envio para aqui (teste-na-producao, 10/10): o Newman do CI cria contas
 * {@code @vupth.test} na producao, e cada aviso gastava a cota da Brevo e a reputacao do remetente.
 */
final class ReservedEmailDomains {

    private static final Set<String> RESERVED_TLDS = Set.of("test", "invalid", "example", "localhost");
    private static final Set<String> RESERVED_DOMAINS = Set.of("example.com", "example.net", "example.org");

    private ReservedEmailDomains() {
    }

    /** {@code true} quando o destinatario e de um dominio reservado de teste. */
    static boolean isReserved(String email) {
        if (email == null) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String raw = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        String domain = raw.endsWith(".") ? raw.substring(0, raw.length() - 1) : raw;
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        return RESERVED_TLDS.contains(tld)
                || RESERVED_DOMAINS.contains(domain)
                || RESERVED_DOMAINS.stream().anyMatch(d -> domain.endsWith("." + d));
    }
}
