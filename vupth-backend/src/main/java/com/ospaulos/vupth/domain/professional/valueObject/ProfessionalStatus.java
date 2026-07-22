package com.ospaulos.vupth.domain.professional.valueObject;

/**
 * Status de trabalho do profissional (S1 — status do profissional → {@code open_status} derivado).
 *
 * <p>O {@code open_status} do estabelecimento deixa de ser um booleano escrito na mao e passa a ser
 * <b>derivado</b> destes status (fatia 2): {@code aberto ⇔ ∃ profissional afiliado em
 * {@link #WORKING} ou {@link #ON_BREAK}}. Nesta fatia 1 o status apenas e persistido e exposto —
 * a derivacao do {@code open_status} entra na fatia seguinte.</p>
 *
 * <ul>
 *   <li>{@link #WORKING} (trabalhando): em expediente, conta para a loja abrir. Exige ≥1 servico.</li>
 *   <li>{@link #ON_BREAK} (intervalo): sub-estado de trabalhando — <b>conta como aberto</b>
 *       (a loja nao fecha no almoco de quem vai voltar). Exige ≥1 servico.</li>
 *   <li>{@link #OFF} (indisponivel): fora de expediente, <b>nao conta</b>. Nao pode entrar aqui
 *       com atendimento ativo ou fila propria.</li>
 * </ul>
 */
public enum ProfessionalStatus {
    WORKING,
    ON_BREAK,
    OFF;

    /** Estados que contam como "presenca" (o estabelecimento fica aberto na fatia 2). */
    public boolean countsAsOpen() {
        return this == WORKING || this == ON_BREAK;
    }
}
