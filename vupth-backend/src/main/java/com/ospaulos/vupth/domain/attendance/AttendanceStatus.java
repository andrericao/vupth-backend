package com.ospaulos.vupth.domain.attendance;

public enum AttendanceStatus {
    SCHEDULED,
    IN_PROGRESS,
    FINISHED,
    CANCELLED,
    PAUSED,
    /**
     * Cliente que nao chegou a tempo (no-show parcial): o profissional o "pula", ele
     * cai uma posicao na fila (item #19) — mas NAO sai. Continua na fila como LATE,
     * pode voltar a SCHEDULED (undo) ou ser atendido direto (start) quando chegar.
     */
    LATE,
    /**
     * Intencao de atendimento (backlog #4): o cliente manifestou a intencao de ser atendido
     * num horario PRETENDIDO futuro ({@code desiredTime}) — tipicamente com a loja fechada ou o
     * dia cheio. A INTENTION fica <b>FORA da fila viva</b>: NAO aparece em {@code professionals-with-queue}
     * (nem waiting_list nem current_attendance) e NAO conta como atendimento ativo. So passa a
     * existir para a fila quando e <b>promovida a {@link #SCHEDULED}</b> ao se aproximar do horario
     * (~15min antes), pegando a posicao do ETA daquele instante (Modelo A — sem reserva de slot).
     */
    INTENTION,

    /**
     * O <b>profissional</b> retirou o cliente da fila — decisao do Andre (18/09), entregue em 24/09.
     * Terminal, como o {@link #CANCELLED}, e a diferenca e <b>de quem foi o ato</b>: {@code CANCELLED}
     * e o cliente desistindo, {@code REMOVED} e a loja tirando. Era um status so, e o app nao tinha
     * como contar a historia certa para o cliente ("voce saiu" x "voce foi retirado").
     *
     * <p>Hoje chega aqui a remocao pelo profissional ({@code DELETE /professional-queue/{id}/remove})
     * e a escalada de no-show ({@code /no-show}, que ja audita o motivo no
     * {@code ClientPenaltyEvent}). Linhas anteriores a 24/09 <b>seguem {@code CANCELLED}</b>: nao da
     * para reconstruir quem retirou, e inventar seria pior que a ausencia.</p>
     */
    REMOVED;

    /**
     * D12: finalizar aceita o atendimento em andamento E o pausado — da pausa, o profissional
     * retoma ou encerra ali. Sem isso, quem pausou retomava so para poder finalizar.
     */
    public boolean isFinishable() {
        return this == IN_PROGRESS || this == PAUSED;
    }

    /**
     * Saiu da fila sem ser atendido — o cliente desistiu ({@link #CANCELLED}) ou a loja o retirou
     * ({@link #REMOVED}).
     *
     * <p>Existe para que a separacao dos dois nao vire um enumerar-a-mao espalhado pelo codigo: quem
     * so precisa saber "esta fora" pergunta aqui, e nao esquece o {@code REMOVED} ao adicionar um
     * terceiro caso. As consultas da fila nao usam isto — elas trabalham por LISTA BRANCA
     * ({@code status IN ('SCHEDULED','LATE',...)}), e foi o que permitiu criar o {@code REMOVED} sem
     * varrer o repositorio atras de negacoes.</p>
     */
    public boolean leftWithoutService() {
        return this == CANCELLED || this == REMOVED;
    }
}
