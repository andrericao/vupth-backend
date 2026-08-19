package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

/**
 * Item #38 — o mesmo cliente tentou entrar na fila do MESMO profissional tendo ja um atendimento
 * ocupado (aguardando, atrasado, em andamento ou pausado). Mapeada para HTTP 409 com code
 * {@code CLIENT_ALREADY_IN_QUEUE}.
 *
 * <p><b>Por que 409 e nao 422.</b> Nada no corpo esta errado — o mesmo request valeria daqui a
 * pouco, quando o atendimento anterior terminar. E conflito com o estado atual, igual aos outros
 * portoes de fila (`ESTABLISHMENT_NOT_OPEN`, `ACCOUNT_DELETE_NOT_ALLOWED`).
 *
 * <p><b>Raio (decisao do Andre em 15/08, D1):</b> bloqueia o par <b>cliente x profissional</b>. Nao
 * bloqueia o mesmo cliente em profissionais diferentes nem em lojas diferentes — inclusive as do
 * mesmo tipo. O bloqueio largo por {@code service_type} foi avaliado e deixado como decisao
 * separada: ele nao fecha o hedging (da para alternar entre "barbearia" e "salao" pelo mesmo corte)
 * e barra o caso legitimo de agendar perto do trabalho hoje e perto de casa amanha. O controle do
 * no-show entre lojas ja existe e e o padrao de mercado para fila: os <b>strikes</b>.
 *
 * <p>⚠️ <b>A mensagem nao diz onde a pessoa esta.</b> Como o raio e o mesmo profissional, quem
 * recebe o erro ja sabe de qual fila se trata — nao ha nada a revelar. Se um dia o raio abrir para
 * outras lojas, a mensagem passa a vazar localizacao e precisa ser repensada junto.
 */
public class ClientAlreadyInQueueException extends RuntimeException {

    /** Guardados para o log — a mensagem que vai ao usuario nao carrega id. */
    private final UUID clientId;
    private final UUID professionalId;

    public ClientAlreadyInQueueException(UUID clientId, UUID professionalId) {
        super("Voce ja esta na fila deste profissional. "
                + "Conclua ou cancele o atendimento atual antes de entrar de novo.");
        this.clientId = clientId;
        this.professionalId = professionalId;
    }

    public UUID getClientId() {
        return clientId;
    }

    public UUID getProfessionalId() {
        return professionalId;
    }
}
