package com.ospaulos.vupth.domain.serviceItem.service;

import com.ospaulos.vupth.domain.serviceItem.model.ServiceTypeKeyword;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceTypeKeywordRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.infrastructure.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sinonimos adicionados pelos profissionais, por tipo, numa unica leitura em cache. O GET /service-types
 * consultava o banco uma vez por tipo a cada chamada; agora le a tabela inteira so quando o cache vence
 * ou quando alguem adiciona um sinonimo.
 */
@Component
@RequiredArgsConstructor
public class ServiceTypeKeywordCatalog {

    private final ServiceTypeKeywordRepository repository;

    @Cacheable(cacheNames = CacheConfig.SERVICE_TYPE_KEYWORDS, key = "'all'")
    public Map<ServiceItemType, Set<String>> addedKeywords() {
        Map<ServiceItemType, Set<String>> byType = new EnumMap<>(ServiceItemType.class);
        for (ServiceTypeKeyword k : repository.findAll()) {
            byType.computeIfAbsent(k.getServiceItemType(), t -> new TreeSet<>()).add(k.getKeyword());
        }
        byType.replaceAll((type, keywords) -> Set.copyOf(keywords));
        return Map.copyOf(byType);
    }

    @CacheEvict(cacheNames = CacheConfig.SERVICE_TYPE_KEYWORDS, allEntries = true)
    public void evict() {
        // so o evict (que, com o gerenciador transacional, espera o commit)
    }
}
