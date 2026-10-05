package com.ospaulos.vupth.domain.serviceItem.service.impl;

import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceTypeSearchResponse;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceTypeKeywordAlreadyExistsException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceTypeKeyword;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceTypeKeywordRepository;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceTypeKeywordCatalog;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceTypeKeywordService;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceTypeKeywordServiceImpl implements ServiceTypeKeywordService {

    private final ServiceTypeKeywordRepository repository;
    private final ServiceTypeKeywordCatalog catalog;

    @Override
    @Transactional(readOnly = true)
    public List<ServiceTypeSearchResponse> search(String query) {
        String q = normalize(query);
        List<ServiceTypeSearchResponse> result = new ArrayList<>();

        for (ServiceItemType type : ServiceItemType.values()) {
            Set<String> keywords = mergedKeywords(type);
            String displayNorm = normalize(type.getDisplayName());
            String nameNorm = normalize(type.name());

            if (q.isBlank()) {
                // Sem filtro: lista todos (para o "i" / navegacao), sem destacar match.
                result.add(toResponse(type, keywords, null));
                continue;
            }

            // casa pelo sinonimo (preferindo o que casou) ou pelo proprio nome do servico
            String matched = keywords.stream()
                    .filter(k -> k.contains(q))
                    .findFirst()
                    .orElse(null);

            boolean matchesName = displayNorm.contains(q) || nameNorm.contains(q);
            if (matched != null || matchesName) {
                result.add(toResponse(type, keywords, matched));
            }
        }
        return result;
    }

    @Override
    @Transactional
    public ServiceTypeSearchResponse addKeyword(ServiceItemType type, String keyword, UUID userId) {
        String normalized = normalize(keyword);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("keyword vazia após normalização");
        }

        // dedup contra a base do enum e os ja adicionados
        boolean inBase = type.getKeywords().stream().anyMatch(k -> normalize(k).equals(normalized));
        if (inBase || repository.existsByServiceItemTypeAndKeyword(type, normalized)) {
            throw new ServiceTypeKeywordAlreadyExistsException(type, normalized);
        }

        repository.save(new ServiceTypeKeyword(type, normalized, userId));
        catalog.evict();
        log.info("[ServiceTypeKeyword] add type={} keyword='{}' by={}", type, normalized, userId);

        // Direto do banco, nao do cache: o evict so vale apos o commit, e ler o cache aqui dentro
        // poderia guardar nele uma linha que ainda nao foi commitada.
        Set<String> merged = baseKeywords(type);
        repository.findByServiceItemType(type).forEach(k -> merged.add(k.getKeyword()));
        return toResponse(type, merged, normalized);
    }

    /** Sinonimos-base (enum) + adicionados (catalogo em cache), normalizados e ordenados. */
    private Set<String> mergedKeywords(ServiceItemType type) {
        Set<String> merged = baseKeywords(type);
        merged.addAll(catalog.addedKeywords().getOrDefault(type, Set.of()));
        return merged;
    }

    private Set<String> baseKeywords(ServiceItemType type) {
        Set<String> base = new TreeSet<>();
        type.getKeywords().forEach(k -> base.add(normalize(k)));
        return base;
    }

    private ServiceTypeSearchResponse toResponse(ServiceItemType type, Set<String> keywords, String matched) {
        List<String> serviceTypes = type.getServiceTypes().stream()
                .map(ServiceType::name)
                .sorted()
                .toList();
        return new ServiceTypeSearchResponse(
                type.name(),
                type.getDisplayName(),
                serviceTypes,
                List.copyOf(keywords),
                matched
        );
    }

    /** minuscula, sem acento, trim e espacos colapsados. Nunca retorna null. */
    private String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .replaceAll("\\s+", " ");
        return n;
    }
}
