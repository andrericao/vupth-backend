package com.ospaulos.vupth.domain.professional.valueObject;

/**
 * Rotulo do CLIENTE para o profissional (S1 — fatia 4). Projecao read-only derivada de
 * {@code work_status} + {@code accepting_new} — nunca persistido, calculado no read-model
 * ({@code professionals-with-queue}, GET e WS).
 *
 * <p>Tabela do handoff Partner (secao "Rotulo do cliente"):</p>
 * <ul>
 *   <li>{@link #DISPONIVEL}: em expediente ({@code WORKING}/{@code ON_BREAK}) e aceitando novos —
 *       o cliente pode entrar na fila. {@code ON_BREAK} projeta DISPONIVEL (nao ha rotulo
 *       "EM INTERVALO" — a espera ja embute o intervalo via ETA meal-aware).</li>
 *   <li>{@link #ENCERRANDO}: em expediente com a fila fechada ({@code accepting_new=false},
 *       drenando) — quem ja esta sera atendido, cliente novo NAO entra (espelha o gate 409
 *       {@code PROFESSIONAL_NOT_ACCEPTING_NEW}, que nao distingue WORKING/ON_BREAK).</li>
 *   <li>{@link #INDISPONIVEL}: {@code OFF} (fora de expediente) — cliente pode agendar via
 *       INTENTION (#4) para quando a agenda estiver liberada.</li>
 * </ul>
 */
public enum ProfessionalAvailability {
    DISPONIVEL,
    ENCERRANDO,
    INDISPONIVEL;

    /**
     * Deriva o rotulo. Consistente com o gate de entrada da fila viva: DISPONIVEL ⇔ o
     * {@code POST /attendances} do cliente novo passaria.
     */
    public static ProfessionalAvailability derive(ProfessionalStatus workStatus, Boolean acceptingNew) {
        if (workStatus == null || !workStatus.countsAsOpen()) {
            return INDISPONIVEL;
        }
        return Boolean.FALSE.equals(acceptingNew) ? ENCERRANDO : DISPONIVEL;
    }
}
