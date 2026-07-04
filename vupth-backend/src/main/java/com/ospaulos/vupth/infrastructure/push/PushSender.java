package com.ospaulos.vupth.infrastructure.push;

import java.util.List;
import java.util.Map;

/**
 * Envio de notificacoes push para uma lista de device tokens (backlog #1).
 *
 * <p>Abstrai o provedor (Expo em producao, no-op em teste/staging). Implementacoes devem ser
 * <b>best-effort</b>: nunca lancar — falha de rede/provedor e logada e engolida, pois o push
 * e um canal auxiliar e nao pode quebrar a operacao principal.</p>
 */
public interface PushSender {

    void send(List<String> tokens, String title, String body, Map<String, Object> data);
}
