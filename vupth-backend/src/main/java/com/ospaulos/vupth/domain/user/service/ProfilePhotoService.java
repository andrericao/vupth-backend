package com.ospaulos.vupth.domain.user.service;

import com.ospaulos.vupth.domain.shared.storage.ImageStorage;
import com.ospaulos.vupth.domain.user.exception.InvalidProfilePhotoException;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Foto de perfil (S22 do Partner; storage decidido pelo Andre em 25/09: Cloudflare R2).
 *
 * <p>O backend recebe o arquivo e o grava — o app nao conhece credencial nenhuma, e a validacao mora
 * num lugar so. JPEG, PNG ou WebP, ate 2 MB, conferidos pelos bytes do arquivo e nao pelo que o
 * cliente declara. Cada upload ganha uma chave nova (a URL muda, o cache nunca serve a foto velha) e
 * a anterior e apagada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProfilePhotoService {

    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private final UserRepository userRepository;
    private final ImageStorage imageStorage;

    public String upload(UUID userId, byte[] content) {
        String[] type = validateImage(content);
        BaseUser user = userRepository.findById(userId).orElseThrow();

        String url = imageStorage.store(
                "profile/" + userId + "/" + UUID.randomUUID() + "." + type[1], content, type[0]);
        String previous = user.getProfileImageUrl();
        user.setProfileImageUrl(url);
        userRepository.save(user);
        deleteQuietly(previous);
        log.info("[ProfilePhoto] user={} nova foto {}", userId, url);
        return url;
    }

    public void remove(UUID userId) {
        BaseUser user = userRepository.findById(userId).orElseThrow();
        String previous = user.getProfileImageUrl();
        user.setProfileImageUrl(null);
        userRepository.save(user);
        deleteQuietly(previous);
    }

    /** Apagar a foto velha e limpeza: se falhar, a foto nova ja esta no lugar e nada quebra. */
    public void deleteQuietly(String url) {
        if (url == null) {
            return;
        }
        try {
            imageStorage.deleteByUrl(url);
        } catch (Exception e) {
            log.warn("[ProfilePhoto] nao apagou {}: {}", url, e.getMessage());
        }
    }

    /**
     * As regras de imagem, reaproveitadas pela foto da loja (Andre, 26/09): vazia, acima de 2 MB ou que
     * nao seja JPEG/PNG/WebP pelos bytes -> {@link InvalidProfilePhotoException} (400 INVALID_PROFILE_PHOTO).
     *
     * @return {content-type, extensao}
     */
    public static String[] validateImage(byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidProfilePhotoException("A foto esta vazia.");
        }
        if (content.length > MAX_BYTES) {
            throw new InvalidProfilePhotoException("A foto passa de 2 MB.");
        }
        ImageType type = ImageType.detect(content);
        if (type == null) {
            throw new InvalidProfilePhotoException("A foto precisa ser JPEG, PNG ou WebP.");
        }
        return new String[]{type.contentType, type.extension};
    }

    enum ImageType {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp");

        final String contentType;
        final String extension;

        ImageType(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        /** Pela assinatura dos primeiros bytes — o nome e o Content-Type vem do cliente e mentem. */
        static ImageType detect(byte[] b) {
            if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
                return JPEG;
            }
            if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
                return PNG;
            }
            if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
                return WEBP;
            }
            return null;
        }
    }
}
