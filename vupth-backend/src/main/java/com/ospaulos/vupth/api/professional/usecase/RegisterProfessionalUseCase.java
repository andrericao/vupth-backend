package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.api.professional.dto.request.ProfessionalRegisterRequest;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.professional.exception.UserAlreadyProfessionalException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalIntervalService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Cadastro de profissional sem establishment (app parceiro — fluxo "sou profissional").
 *
 * Diferença do QuickCreateProfessionalUseCase:
 *   - Não exige establishmentId.
 *   - O profissional fica com establishment = null.
 *   - Para trabalhar em uma loja, o owner deve vinculá-lo via
 *     POST /establishment/{id}/professionals.
 *
 * HTTP: POST /professional/register
 * Auth: ROLE_CLIENT (qualquer autenticado)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterProfessionalUseCase {

    private final UserRepository userRepository;
    private final ProfessionalIntervalService intervalService;

    @Transactional
    public ProfessionalQuickCreateResponse execute(UUID userId, ProfessionalRegisterRequest request) {

        log.info("[RegisterProfessional] execute — user={} name={}", userId, request.name());

        // 1. Load user
        BaseUser user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // 2. Already a professional?
        if (user.hasRole(RoleType.PROFESSIONAL)) {
            throw new UserAlreadyProfessionalException(
                    "User already has a professional profile. " +
                    "To work at another establishment, use the affiliation feature."
            );
        }

        // 3. Set name if not already set
        if ((user.getFirstName() == null || user.getFirstName().isBlank()) && request.name() != null) {
            user.setFirstName(request.name().trim());
        }

        // 4. Create professional profile via cascade (same pattern as QuickCreate)
        //    establishment = null — will be set later by owner via /establishment/{id}/professionals
        user.addProfessionalProfile();
        Professional professional = user.getProfessional();
        professional.setSpecialty(request.specialty());
        // professional.establishment intentionally left null

        userRepository.save(user);

        // Seed refeicoes padrao (almoco/janta) — spec item #11a
        intervalService.seedDefaultMeals(professional);

        log.info("[RegisterProfessional] professional created — id={} name={} (no establishment yet)",
                professional.getId(), user.getFirstName());

        return new ProfessionalQuickCreateResponse(
                professional.getId(),
                user.getFirstName(),
                professional.getSpecialty(),
                user.getNickname(),
                null,  // no establishment yet
                null,
                professional.getIsAvailable(),
                professional.getWorkStatus(),
                "Professional profile created! Ask an owner to add you to their establishment, " +
                "or use /professional/affiliate when you have an establishment ID."
        );
    }
}
