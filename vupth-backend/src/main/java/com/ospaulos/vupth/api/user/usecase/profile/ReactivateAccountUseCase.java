package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Reativar a conta desativada (desativar-conta, ajuste do Andre 08/10: o login nao reativa sozinho —
 * o app oferece "Reativar" e chama esta rota). As lojas do dono voltam a vitrine, fechadas, e os
 * profissionais delas recebem {@code ESTABLISHMENT_REACTIVATED}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactivateAccountUseCase {

    static final String REACTIVATED_TYPE = "ESTABLISHMENT_REACTIVATED";

    private final UserRepository userRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final PushNotificationPublisher pushPublisher;

    @Transactional
    public void execute(UUID userId) {
        BaseUser user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (!user.isAccountDeactivated()) {
            return; // ja em uso
        }

        if (user.hasOwnerProfile()) {
            for (Establishment establishment : establishmentRepository.findByOwnerId(userId)) {
                if (establishment.getStatus() != EstablishmentStatus.DEACTIVATED) {
                    continue;
                }
                establishment.setStatus(EstablishmentStatus.ACTIVE);
                establishment.setOpenStatus(OpenStatus.CLOSED);
                establishmentRepository.save(establishment);
                for (Professional professional : professionalRepository.findByEstablishmentId(establishment.getId())) {
                    if (userId.equals(professional.getId())) {
                        continue;
                    }
                    pushPublisher.notifyUser(professional.getId(), "Loja reativada",
                            "A loja " + establishment.getName() + " voltou a aparecer para os clientes.",
                            Map.of("type", REACTIVATED_TYPE, "establishmentId", String.valueOf(establishment.getId())));
                }
                log.info("[ReactivateAccount] establishment={} back to ACTIVE (CLOSED)", establishment.getId());
            }
        }

        user.setDeactivatedAt(null);
        userRepository.save(user);
        log.info("[ReactivateAccount] user={} reactivated", userId);
    }
}
