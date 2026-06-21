package com.ospaulos.vupth.domain.serviceItem.service;

import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceTypeSearchResponse;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;

import java.util.List;
import java.util.UUID;

public interface ServiceTypeKeywordService {

    /**
     * Resolve o termo (sinonimo regional, nome do servico) para os tipos canonicos.
     * Sem termo (null/blank), lista todos os tipos com seus sinonimos (para o "i").
     */
    List<ServiceTypeSearchResponse> search(String query);

    /**
     * Adiciona um sinonimo a um tipo de servico. Valor normalizado antes de gravar.
     * Lanca ServiceTypeKeywordAlreadyExistsException se ja existir (base do enum ou
     * adicionado). So profissionais.
     */
    ServiceTypeSearchResponse addKeyword(ServiceItemType type, String keyword, UUID userId);
}
