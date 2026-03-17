package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.api.professional.dto.request.ProfessionalRequest;
import com.ospaulos.vupth.api.professional.exception.UserAlreadyProfessionalException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateProfessionalUseCase {

    private final UserRepository userRepository;
    private final ProfessionalRepository professionalRepository;

    @Transactional
    public void execute(UUID userId, ProfessionalRequest request) {

        // 1. Load user
        BaseUser user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // 2. Validate role not already present
        if (user.hasRole(RoleType.PROFESSIONAL)) {
            throw new UserAlreadyProfessionalException("MUDAR");
        }

        // 3. Add role
        // todo: Method in BaseUser
        // todo: replace with a dedicated method instead of the generic addProfessionalProfile
        user.addRole(RoleType.PROFESSIONAL);

        // 4. Create professional profile EXPLICITLY (not automatic)
        // TODO: MEXER
        Professional professional = new Professional(user);
        professional.setSpecialty(request.speciality());
        professionalRepository.save(professional);

        // 5. Save user
        userRepository.save(user);



        log.info("User {} promoted to PROFESSIONAL", user.getEmail());
    }
}

