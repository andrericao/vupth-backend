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
    INTENTION;

    /**
     * D12: finalizar aceita o atendimento em andamento E o pausado — da pausa, o profissional
     * retoma ou encerra ali. Sem isso, quem pausou retomava so para poder finalizar.
     */
    public boolean isFinishable() {
        return this == IN_PROGRESS || this == PAUSED;
    }
}
