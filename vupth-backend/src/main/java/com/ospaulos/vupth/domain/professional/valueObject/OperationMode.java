package com.ospaulos.vupth.domain.professional.valueObject;

/**
 * Como o profissional trabalha (D9, decidido por PROFISSIONAL em 05/09 e reafirmado em 25/09): numa
 * mesma loja um pode trabalhar em fila e outro so com agenda.
 *
 * <ul>
 *   <li>{@code QUEUE} — o normal: fila viva (walk-in, pedido de corte, horario-alvo) e agendamento.</li>
 *   <li>{@code AGENDA_ONLY} — so agendamento ({@code POST /attendances/intentions}); o cliente nao entra
 *       na fila por conta propria. O agendado segue entrando na fila na hora dele.</li>
 * </ul>
 */
public enum OperationMode {
    QUEUE,
    AGENDA_ONLY
}
