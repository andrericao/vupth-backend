package com.ospaulos.vupth.infrastructure.asaas;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A conta do Asaas (testes-asaas-sandbox, fatia 1). So o SANDBOX: sem {@code ASAAS_API_KEY}, ou com uma URL que nao seja
 * de sandbox, o cliente fica desligado e nenhuma cobranca sai (a producao nunca cobra por aqui). O webhook so aceita o aviso
 * que traz o {@code ASAAS_WEBHOOK_TOKEN}.
 */
@Component
public class AsaasProperties {

    public static final String DEFAULT_SANDBOX_URL = "https://api-sandbox.asaas.com/v3";

    private final String apiKey;
    private final String baseUrl;
    private final String webhookToken;

    public AsaasProperties(@Value("${ASAAS_API_KEY:}") String apiKey,
                           @Value("${ASAAS_BASE_URL:" + DEFAULT_SANDBOX_URL + "}") String baseUrl,
                           @Value("${ASAAS_WEBHOOK_TOKEN:}") String webhookToken) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_SANDBOX_URL : baseUrl.trim().replaceAll("/+$", "");
        this.webhookToken = webhookToken == null ? "" : webhookToken.trim();
    }

    /** O cliente HTTP so liga com a chave presente e uma URL de sandbox: a producao fica desligada. */
    public boolean clientEnabled() {
        return !apiKey.isEmpty() && baseUrl.toLowerCase(java.util.Locale.ROOT).contains("sandbox");
    }

    public boolean webhookEnabled() {
        return !webhookToken.isEmpty();
    }

    public String apiKey() {
        return apiKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String webhookToken() {
        return webhookToken;
    }
}
