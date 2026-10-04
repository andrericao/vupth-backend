package com.ospaulos.vupth.domain.queue.swap;

/**
 * D14 (Andre, 24/09): a troca de lugar so acontece entre atendimentos com ate 10 minutos de diferenca
 * de duracao — "um cliente que tem um servico de 30 minutos nao pode trocar com um cliente que tem 2
 * horas". Trocar os dois desloca todo mundo que vem depois pela diferenca.
 *
 * <p>A regra mora em quem executa a troca: a oferta aberta ({@code /swap-offers}) e a proposta direta
 * ({@code /swap-requests}) usam esta mesma funcao.</p>
 */
public final class SwapDurationRule {

    public static final int MAX_GAP_MINUTES = 10;

    private SwapDurationRule() {
    }

    /** As duas duracoes estimadas (minutos) cabem na mesma troca? */
    public static boolean compatible(int minutesA, int minutesB) {
        return Math.abs(minutesA - minutesB) <= MAX_GAP_MINUTES;
    }
}
