package com.ospaulos.vupth.infrastructure.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * PushSender no-op — apenas loga, nao chama provedor externo. Ativo quando
 * {@code vupth.push.provider=none} (testes/staging), evitando HTTP para o Expo durante a suite.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vupth.push", name = "provider", havingValue = "none")
public class NoOpPushSender implements PushSender {

    @Override
    public void send(List<String> tokens, String title, String body, Map<String, Object> data) {
        log.info("[PUSH:noop] {} token(s) title='{}' body='{}'",
                tokens == null ? 0 : tokens.size(), title, body);
    }
}
