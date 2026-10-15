package com.ospaulos.vupth.domain.campaign.exception;

import org.springframework.http.HttpStatus;

/**
 * Recusa do cupom (DEC-64), com o status e o codigo que o app traduz:
 * <ul>
 *   <li>404 {@code CAMPAIGN_NOT_FOUND} — cupom que nao existe;</li>
 *   <li>409 {@code CAMPAIGN_NOT_AVAILABLE} — desligada, fora da janela ou sem vaga;</li>
 *   <li>409 {@code CAMPAIGN_ALREADY_REDEEMED} — a pessoa ja aceitou;</li>
 *   <li>403 {@code CAMPAIGN_NOT_FOR_YOU} — o perfil nao e o da campanha, ou nao e cliente novo.</li>
 * </ul>
 */
public class CampaignException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private CampaignException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static CampaignException notFound() {
        return new CampaignException(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND", "Cupom nao encontrado.");
    }

    public static CampaignException notAvailable() {
        return new CampaignException(HttpStatus.CONFLICT, "CAMPAIGN_NOT_AVAILABLE", "Esta campanha nao esta disponivel agora.");
    }

    public static CampaignException alreadyRedeemed() {
        return new CampaignException(HttpStatus.CONFLICT, "CAMPAIGN_ALREADY_REDEEMED", "Voce ja usou este cupom.");
    }

    public static CampaignException notForYou() {
        return new CampaignException(HttpStatus.FORBIDDEN, "CAMPAIGN_NOT_FOR_YOU", "Este cupom nao vale para o seu perfil.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
