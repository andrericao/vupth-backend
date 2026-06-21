package com.ospaulos.vupth.domain.serviceItem.exception;

import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;

/**
 * O sinonimo/palavra-chave ja existe para o tipo de servico (base do enum ou
 * adicionado por outro profissional). Nao se pode cadastrar palavra repetida.
 */
public class ServiceTypeKeywordAlreadyExistsException extends RuntimeException {

    public ServiceTypeKeywordAlreadyExistsException(ServiceItemType type, String keyword) {
        super("Keyword '" + keyword + "' already exists for service type " + type.name() + ".");
    }
}
