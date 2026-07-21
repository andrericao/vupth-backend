package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.api.professional.dto.request.ProfessionalQuickCreateRequest;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.professional.exception.UserAlreadyProfessionalException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalIntervalService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Cadastro simplificado de profissional (MVP):
 *
 * Fluxo:
 *  1. Authenticated user provides only their first name and the establishment ID.
 *  2. The name is saved to BaseUser.firstName — other fields (lastName, phone, photo) are optional.
 *  3. The PROFESSIONAL role and profile are created via addProfessionalProfile(),
 *     which sets user.professional = new Professional(user) so that the cascade=ALL
 *     on BaseUser.professional persists the entity correctly (avoids @MapsId + merge() conflict).
 *  4. Professional is created with a fixed ID = BaseUser.id (MapsId) — immutable.
 *  5. Specialty and establishment are set after the initial persist, then saved again.
 *
 * Por que NÃO usar professionalRepository.save() diretamente:
 *  Professional uses @MapsId — its id is manually set to BaseUser.id before save().
 *  Spring Data JPA sees id != null → calls em.merge() instead of em.persist().
 *  em.merge() on a brand-new @MapsId entity causes a detached-entity conflict in Hibernate 6.
 *  The safe pattern mirrors Client and Owner: create via addXxxProfile() on BaseUser,
 *  let cascade=ALL handle the persist, then reload and configure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickCreateProfessionalUseCase {

    private final UserRepository userRepository;
    private final ProfessionalRepository professionalRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalIntervalService intervalService;

    @Transactional
    public ProfessionalQuickCreateResponse execute(UUID userId, ProfessionalQuickCreateRequest request) {

        log.info("QuickCreateProfessionalUseCase.execute() — user={} name={} estab={}",
                userId, request.name(), request.establishmentId());

        // 1. Load authenticated user
        BaseUser user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // 2. Already a professional?
        if (user.hasRole(RoleType.PROFESSIONAL)) {
            throw new UserAlreadyProfessionalException(
                    "User already has a professional profile. To work at another establishment, use the affiliation feature."
            );
        }

        // 3. Valida o establishment
        Establishment establishment = establishmentRepository.findById(request.establishmentId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Establishment not found: " + request.establishmentId()));

        // 4. Save firstName to BaseUser if not set yet
        if ((user.getFirstName() == null || user.getFirstName().isBlank()) && request.name() != null) {
            user.setFirstName(request.name().trim());
        }

        // 5. Create Professional via cascade — same pattern used by Client and Owner.
        //    addProfessionalProfile() sets user.professional = new Professional(user),
        //    which also adds PROFESSIONAL role. cascade=ALL on BaseUser.professional
        //    issues em.persist() (not merge) — avoids the @MapsId + merge() conflict.
        //    After save, user.professional IS the persisted managed instance — no extra findById needed.
        user.addProfessionalProfile();
        Professional professional = user.getProfessional(); // in-memory reference BEFORE save
        professional.setSpecialty(request.specialty());     // set BEFORE cascade persist
        professional.setEstablishment(establishment);       // set BEFORE cascade persist
        userRepository.save(user);                          // cascade persists Professional with all fields set

        // Seed refeicoes padrao (almoco/janta) — spec item #11a
        intervalService.seedDefaultMeals(professional);

        log.info("Professional created — id={} name={} estab={}",
                professional.getId(), user.getFirstName(), establishment.getName());

        return new ProfessionalQuickCreateResponse(
                professional.getId(),
                user.getFirstName(),
                professional.getSpecialty(),
                user.getNickname(),
                establishment.getId(),
                establishment.getName(),
                professional.getIsAvailable(),
                "Professional created! Your ID is permanent. Fill in the rest whenever you want."
        );
    }
}
