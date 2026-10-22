package com.ospaulos.vupth.api.user.exception;

/**
 * A conta nao pode ser apagada AGORA porque ha compromisso vivo com terceiros — fila, atendimento
 * em andamento ou intencao pendente. Vira {@code 409 ACCOUNT_DELETE_NOT_ALLOWED}.
 *
 * <p>E sempre um bloqueio TEMPORARIO e resolvivel pelo proprio titular dentro do app (sair da
 * fila, encerrar o atendimento, esperar a loja esvaziar) — nunca um "nao pode apagar". Isso
 * importa: as lojas de app exigem que exista um caminho de exclusao, e um bloqueio permanente
 * reprovaria a publicacao. Por isso a mensagem diz O QUE fazer, nao so que falhou.</p>
 */
public class AccountDeleteNotAllowedException extends RuntimeException {

    public AccountDeleteNotAllowedException(String reason) {
        super(reason);
    }

    public static AccountDeleteNotAllowedException clientInQueue() {
        return new AccountDeleteNotAllowedException(
                "Você ainda está em uma fila. Saia da fila (ou espere ser atendido) antes de excluir a sua conta.");
    }

    public static AccountDeleteNotAllowedException professionalWithActiveAttendances() {
        return new AccountDeleteNotAllowedException(
                "Você ainda tem atendimentos em andamento. Termine ou cancele esses atendimentos antes de excluir a sua conta.");
    }

    public static AccountDeleteNotAllowedException establishmentWithActiveQueue() {
        return new AccountDeleteNotAllowedException(
                "A sua loja ainda tem clientes na fila. Espere a fila esvaziar antes de excluir a sua conta.");
    }
}
