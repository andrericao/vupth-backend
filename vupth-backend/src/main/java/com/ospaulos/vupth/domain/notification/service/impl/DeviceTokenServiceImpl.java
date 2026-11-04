package com.ospaulos.vupth.domain.notification.service.impl;

import com.ospaulos.vupth.domain.notification.model.DeviceToken;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import com.ospaulos.vupth.domain.notification.service.DeviceTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DeviceTokenServiceImpl implements DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;

    @Override
    public DeviceToken register(UUID userId, String token, String platform) {
        DeviceToken entity = deviceTokenRepository.findByToken(token)
                .map(existing -> {
                    existing.setUserId(userId);
                    existing.setPlatform(platform);
                    return existing;
                })
                .orElseGet(() -> new DeviceToken(userId, token, platform));
        DeviceToken saved = deviceTokenRepository.save(entity);
        log.info("[PUSH] device token registrado — user={} platform={}", userId, platform);
        return saved;
    }

    @Override
    public void remove(String token, UUID userId) {
        deviceTokenRepository.findByToken(token).ifPresent(existing -> {
            if (!java.util.Objects.equals(existing.getUserId(), userId)) {
                log.warn("[PUSH] remocao de device token de outro usuario ignorada (user={})", userId);
                return;
            }
            deviceTokenRepository.delete(existing);
            log.info("[PUSH] device token removido");
        });
    }
}
