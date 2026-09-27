package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.shared.storage.ImageStorage;
import com.ospaulos.vupth.domain.user.service.ProfilePhotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Foto da LOJA (pedido do Andre, 26/09, via Partner) — a mesma mecanica da foto de perfil: o backend
 * recebe, confere pelos bytes (JPEG/PNG/WebP ate 2 MB), grava no R2 com chave nova e apaga a anterior.
 * Preenche o {@code url_image} que o cliente ja le na lista e no detalhe da loja. So o dono.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EstablishmentPhotoService {

    private final EstablishmentRepository establishmentRepository;
    private final ImageStorage imageStorage;
    private final ProfilePhotoService profilePhotoService;

    public String upload(UUID establishmentId, UUID requesterId, byte[] content) {
        Establishment establishment = ownedBy(establishmentId, requesterId);
        String[] type = ProfilePhotoService.validateImage(content);

        String url = imageStorage.store(
                "establishment/" + establishmentId + "/" + UUID.randomUUID() + "." + type[1], content, type[0]);
        String previous = establishment.getUrlImage();
        establishment.setUrlImage(url);
        establishmentRepository.save(establishment);
        profilePhotoService.deleteQuietly(previous);
        log.info("[EstablishmentPhoto] loja={} nova foto {}", establishmentId, url);
        return url;
    }

    public void remove(UUID establishmentId, UUID requesterId) {
        Establishment establishment = ownedBy(establishmentId, requesterId);
        String previous = establishment.getUrlImage();
        establishment.setUrlImage(null);
        establishmentRepository.save(establishment);
        profilePhotoService.deleteQuietly(previous);
    }

    private Establishment ownedBy(UUID establishmentId, UUID requesterId) {
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));
        if (establishment.getOwner() == null || !establishment.getOwner().getId().equals(requesterId)) {
            throw new EstablishmentAccessDeniedException("Only the owner can change the establishment photo.");
        }
        return establishment;
    }
}
