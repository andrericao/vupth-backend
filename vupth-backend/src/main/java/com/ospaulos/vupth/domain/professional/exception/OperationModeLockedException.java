package com.ospaulos.vupth.domain.professional.exception;

/**
 * O modo so troca no inicio do expediente, antes do primeiro cliente na fila do dia (Andre, 25/09):
 * trocar com gente atendida ou esperando mudaria a regra no meio do dia. HTTP 409 OPERATION_MODE_LOCKED.
 */
public class OperationModeLockedException extends RuntimeException {
    public OperationModeLockedException() {
        super("O modo de funcionamento so pode ser trocado no inicio do expediente, antes do primeiro cliente na fila de hoje.");
    }
}
