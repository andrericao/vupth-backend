package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentQuickCreateRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentQuickCreateResponse;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.owner.repository.OwnerRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Cadastro simplificado de establishment (MVP):
 *
 * Fluxo:
 *  1. The user is already authenticated (JWT) — userId is extracted from the token.
 *  2. If the user has no OWNER profile yet, one is created automatically.
 *  3. Criamos o Establishment com apenas o nome.
 *  4. The generated ID is a fixed UUID v4 — all attendances and service items
 *     futuros referenciam este ID.
 *  5. Optional fields (photo, services, address, hours) can be filled in later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickCreateEstablishmentUseCase {

    private final UserRepository userRepository;
    private final OwnerRepository ownerRepository;
    private final EstablishmentRepository establishmentRepository;

    @Transactional
    public EstablishmentQuickCreateResponse execute(UUID userId, EstablishmentQuickCreateRequest request) {

        log.info("QuickCreateEstablishmentUseCase.execute() — user={} name={}", userId, request.name());

        // 1. Load authenticated user
        BaseUser user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // 2. Ensure OWNER profile exists (lazy creation — no forced pre-registration)
        if (!user.hasRole(RoleType.OWNER)) {
            user.addOwnerProfile();
            userRepository.save(user);
            log.info("Perfil OWNER created automaticamente para user={}", userId);
        }

        Owner owner = ownerRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Owner profile not found after creation"));

        // 3. Create establishment with minimum required data
        Establishment establishment = new Establishment();
        establishment.setName(request.name());
        establishment.setServiceType(request.serviceType());
        establishment.setServices(Set.of(request.serviceType()));
        establishment.setStatus(EstablishmentStatus.ACTIVE);
        establishment.setOpenStatus(OpenStatus.CLOSED);
        establishment.setOwner(owner);

        Establishment saved = establishmentRepository.save(establishment);

        log.info("Establishment created — id={} name={} owner={}", saved.getId(), saved.getName(), userId);

        return new EstablishmentQuickCreateResponse(
                saved.getId(),
                saved.getName(),
                request.serviceType(),
                EstablishmentStatus.ACTIVE.name(),
                OpenStatus.CLOSED.name(),
                owner.getId(),
                saved.getCreatedAt(),
                "Establishment created! Your ID is permanent. Fill in the rest whenever you want."
        );
    }
}
