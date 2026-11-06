package com.ospaulos.vupth.domain.feature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ospaulos.vupth.api.feature.dto.AppFeatureHistoryResponse;
import com.ospaulos.vupth.api.feature.dto.AppFeatureResponse;
import com.ospaulos.vupth.api.feature.dto.AppFeatureUpdateRequest;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * recursos-infraestrutura (Andre, 06/11): a verdade das chaves do app. Na duvida, desligada: chave desconhecida, conta que nao existe,
 * dependencia desligada. A regra, nesta ordem: a chave esta ligada; a dependencia vale para a conta; a conta esta na lista, ou passa
 * no perfil, na regiao e na porcentagem. A porcentagem usa um sorteio fixo por chave e conta: a mesma conta sempre cai no mesmo lado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppFeatureService {

    private final AppFeatureRepository features;
    private final AppFeatureHistoryRepository history;
    private final UserRepository users;
    private final ObjectMapper mapper;

    /** A chave vale para a conta? */
    @Transactional(readOnly = true)
    public boolean isEnabled(String key, UUID userId) {
        return enabledFor(userId).contains(key);
    }

    /** As chaves ligadas para a conta; a que nao vem aqui esta desligada. */
    @Transactional(readOnly = true)
    public List<String> enabledFor(UUID userId) {
        BaseUser user = users.findById(userId).orElse(null);
        if (user == null) {
            return List.of();
        }
        Map<String, AppFeature> all = features.findAll().stream().collect(Collectors.toMap(AppFeature::getKey, Function.identity()));
        return all.values().stream().filter(f -> appliesTo(f, all, user)).map(AppFeature::getKey).sorted().toList();
    }

    @Transactional(readOnly = true)
    public List<AppFeatureResponse> list() {
        return features.findAll().stream().sorted(Comparator.comparing(AppFeature::getKey)).map(AppFeatureResponse::of).toList();
    }

    /** Troca a regra da chave e grava quem, quando, o motivo e o valor anterior. */
    @Transactional
    public AppFeatureResponse update(String key, AppFeatureUpdateRequest request, UUID adminId) {
        AppFeature feature = load(key);
        String previous = json(AppFeatureResponse.of(feature));
        OffsetDateTime now = OffsetDateTime.now();
        feature.apply(request.enabled(), request.percent(), request.profiles().stream().distinct().toList(),
                request.regions().stream().map(String::trim).distinct().toList(),
                request.accounts().stream().map(UUID::toString).distinct().toList(), adminId, now);
        features.save(feature);
        AppFeatureResponse response = AppFeatureResponse.of(feature);
        history.save(new AppFeatureHistory(key, adminId, now, request.reason().trim(), previous, json(response)));
        log.info("[Feature] admin={} mudou chave={} ligada={} percent={}", adminId, key, feature.isEnabled(), feature.getPercent());
        return response;
    }

    @Transactional(readOnly = true)
    public List<AppFeatureHistoryResponse> history(String key) {
        load(key);
        return history.findByFeatureKeyOrderByChangedAtDesc(key).stream()
                .map(h -> new AppFeatureHistoryResponse(h.getChangedBy(), h.getChangedAt(), h.getReason(), h.getPreviousValue(), h.getNewValue()))
                .toList();
    }

    private static boolean appliesTo(AppFeature f, Map<String, AppFeature> all, BaseUser user) {
        if (!f.isEnabled()) {
            return false;
        }
        if (f.getDependsOn() != null) {
            AppFeature parent = all.get(f.getDependsOn());
            if (parent == null || !appliesTo(parent, all, user)) {
                return false;
            }
        }
        if (f.accountList().contains(user.getId().toString())) {
            return true;
        }
        return matchesProfile(f, user) && matchesRegion(f, user) && bucket(f.getKey(), user.getId()) < f.getPercent();
    }

    private static boolean matchesProfile(AppFeature f, BaseUser user) {
        List<String> profiles = f.profileList();
        return profiles.isEmpty()
                || profiles.contains("CLIENT") && user.hasRole(RoleType.CLIENT)
                || profiles.contains("PROFESSIONAL") && user.hasRole(RoleType.PROFESSIONAL)
                || profiles.contains("ESTABLISHMENT") && user.hasRole(RoleType.OWNER);
    }

    /** Regiao: a UF ou a cidade do endereco principal. Sem endereco, fica de fora de chave com regiao. */
    private static boolean matchesRegion(AppFeature f, BaseUser user) {
        List<String> regions = f.regionList();
        if (regions.isEmpty()) {
            return true;
        }
        var main = user.getMainAddress();
        if (main == null || main.getAddress() == null) {
            return false;
        }
        String state = main.getAddress().getState();
        String city = main.getAddress().getCity();
        return regions.stream().anyMatch(r -> r.equalsIgnoreCase(state) || r.equalsIgnoreCase(city));
    }

    /** De 0 a 99, sempre o mesmo para a mesma chave e conta. */
    static int bucket(String key, UUID userId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((key + ":" + userId).getBytes(StandardCharsets.UTF_8));
            int value = ((hash[0] & 0xff) << 24) | ((hash[1] & 0xff) << 16) | ((hash[2] & 0xff) << 8) | (hash[3] & 0xff);
            return Math.floorMod(value, 100);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private AppFeature load(String key) {
        return features.findById(key).orElseThrow(() -> new AppFeatureNotFoundException(key));
    }

    private String json(AppFeatureResponse value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
