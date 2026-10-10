package com.ospaulos.vupth.domain.payment;

/**
 * O pagamento de um atendimento (pagamento-pix, 10/10). E uma flag <b>ao lado</b> do
 * {@code AttendanceStatus}, nao dentro dele — como o iFood (prepaid/pending), o Square e a Fresha
 * fazem: o cliente paga antes ou depois do corte. {@code null} = fora do fluxo (cancelado ou retirado
 * sem pagar, ou anterior a este campo).
 */
public enum PaymentStatus {
    /** Nasce na criacao do atendimento: o cliente ainda nao pagou. */
    PENDING,
    /** O cliente tocou "Ja paguei": falta o profissional (ou o dono) confirmar. */
    AWAITING_CONFIRMATION,
    /** O profissional ou o dono tocou "Recebi". So aqui as multas do atendimento contam como pagas. */
    PAID;

    /** Ainda deve. Finalizado e em aberto, entra no dot do Perfil e barra fila, agenda e pedido de corte. */
    public boolean isOpen() {
        return this == PENDING || this == AWAITING_CONFIRMATION;
    }
}
