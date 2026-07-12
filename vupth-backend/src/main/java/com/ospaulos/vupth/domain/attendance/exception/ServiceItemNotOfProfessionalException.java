package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

/**
 * Partner BUG8 (sintoma c). Lancada quando o atendimento e criado com um {@code serviceItem}
 * que NAO pertence ao profissional escolhido — servico e capacidade do PROFISSIONAL (decisao de
 * produto 2026-06-28): cada {@code ServiceItem} tem dono ({@code professional_id}) e so pode ser
 * agendado com o seu proprio profissional. Como efeito, profissional sem servico nao pode ser
 * marcado (nao ha item valido para ele).
 *
 * Mapeada para HTTP 422 com code SERVICE_ITEM_NOT_OF_PROFESSIONAL.
 */
public class ServiceItemNotOfProfessionalException extends RuntimeException {
    public ServiceItemNotOfProfessionalException(UUID serviceItemId, UUID professionalId) {
        super("Service item " + serviceItemId + " does not belong to professional " + professionalId
                + ". Only the professional's own service items can be scheduled.");
    }
}
