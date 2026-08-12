package com.ospaulos.vupth.infrastructure.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * PushSender no-op — apenas loga, nao chama provedor externo. Ativo quando
 * {@code vupth.push.provider=none} (testes/staging), evitando HTTP para o Expo durante a suite.
 *
 * <p>Devolve todos os tokens como <b>aceitos</b> (backlog #28): sem provedor real nao ha ticket de
 * verdade, e inventar recusa aqui apagaria device bom da base em staging.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vupth.push", name = "provider", havingValue = "none")
public class NoOpPushSender implements PushSender {

    @Override
    public List<PushTicket> send(List<String> tokens, String title, String body, Map<String, Object> data) {
        log.info("[PUSH:noop] {} token(s) title='{}' body='{}'",
                tokens == null ? 0 : tokens.size(), title, body);
        if (tokens == null) {
            return List.of();
        }
        return tokens.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(PushTicket::accepted)
                .toList();
    }
}
