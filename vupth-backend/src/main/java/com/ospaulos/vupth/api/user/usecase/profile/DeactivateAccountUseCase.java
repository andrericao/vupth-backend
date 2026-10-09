package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.api.professional.usecase.LeaveEstablishmentUseCase;
import com.ospaulos.vupth.api.user.exception.AccountDeactivateNotAllowedException;
import com.ospaulos.vupth.api.user.exception.InvalidCurrentPasswordException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import com.ospaulos.vupth.domain.refreshToken.repository.RefreshTokenRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Desativar a conta (desativar-conta, Andre 05/10 e 08/10) — reversivel, ao lado do apagar.
 *
 * <ol>
 *   <li><b>Senha</b> (401) e os <b>portoes</b> do apagar (409 ACCOUNT_DEACTIVATE_NOT_ALLOWED), antes de
 *       qualquer escrita.</li>
 *   <li>O <b>profissional</b> sai da loja onde trabalha (b) — fica livre para pedir vinculo em outra ao voltar.</li>
 *   <li>As lojas do <b>dono</b> saem da vitrine ({@code DEACTIVATED}) e os profissionais delas sao avisados
 *       por push e e-mail, sem serem tirados da loja (5).</li>
 *   <li>Saem os tokens de push e de renovacao: nada mais chega ao aparelho, e o app volta ao login.</li>
 * </ol>
 *
 * <p>A conta segue entrando: o login responde {@code deactivated: true} e o app oferece "Reativar" ou
 * "So consultar" (ajuste do Andre, 08/10). Em "so consultar" ela le, e o que mexe na operacao da 403
 * ({@code DeactivatedAccountInterceptor}).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeactivateAccountUseCase {

    static final String DEACTIVATED_TYPE = "ESTABLISHMENT_DEACTIVATED";

    private final UserRepository userRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LeaveEstablishmentUseCase leaveEstablishmentUseCase;
    private final AccountCommitmentGate commitmentGate;
    private final PasswordEncoder passwordEncoder;
    private final PushNotificationPublisher pushPublisher;
    private final EmailSender emailSender;

    @Transactional
    public void execute(UUID userId, String currentPassword) {
        log.info("[DeactivateAccount] execute — user={}", userId);
        BaseUser user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new InvalidCurrentPasswordException();
        }
        commitmentGate.firstBlocker(user).ifPresent(b -> {
            throw new AccountDeactivateNotAllowedException(b.message());
        });
        if (user.isAccountDeactivated()) {
            return; // ja desativada: nada a refazer
        }

        if (user.hasOwnerProfile()) {
            for (Establishment establishment : establishmentRepository.findByOwnerId(userId)) {
                deactivateEstablishment(establishment, userId);
            }
        }
        if (user.hasProfessionalProfile()) {
            Professional professional = professionalRepository.findById(userId).orElseThrow();
            boolean worksInOwnStore = professional.getEstablishment() != null
                    && professional.getEstablishment().getOwner() != null
                    && userId.equals(professional.getEstablishment().getOwner().getId());
            if (professional.getEstablishment() != null && !worksInOwnStore) {
                leaveEstablishmentUseCase.execute(userId);
                professional = professionalRepository.findById(userId).orElseThrow();
            }
            professional.setIsAvailable(false);
            professional.setWorkStatus(ProfessionalStatus.OFF);
            professionalRepository.save(professional);
        }

        deviceTokenRepository.deleteByUserId(userId);
        refreshTokenRepository.deleteByUserId(userId);
        user.setDeactivatedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("[DeactivateAccount] user={} deactivated", userId);
    }

    /** A loja sai da vitrine, fecha, e cada profissional dela (menos o proprio dono) e avisado. */
    private void deactivateEstablishment(Establishment establishment, UUID ownerId) {
        establishment.setStatus(EstablishmentStatus.DEACTIVATED);
        establishment.setOpenStatus(OpenStatus.CLOSED);
        establishmentRepository.save(establishment);

        String name = establishment.getName();
        for (Professional professional : professionalRepository.findByEstablishmentId(establishment.getId())) {
            if (ownerId.equals(professional.getId())) {
                continue;
            }
            pushPublisher.notifyUser(professional.getId(), "Loja desativada",
                    "A loja " + name + " foi desativada e não aparece mais para os clientes. Recomendamos que você saia dela.",
                    Map.of("type", DEACTIVATED_TYPE, "establishmentId", String.valueOf(establishment.getId())));
            try {
                emailSender.send(professional.getBaseUser().getEmail(), "A loja " + name + " foi desativada",
                        "O dono desativou a loja " + name + ". Ela não aparece mais para os clientes, e a fila não recebe"
                                + " ninguém.\n\nRecomendamos que você saia dela pelo app (Vínculo) e peça vínculo em outra loja.");
            } catch (EmailDeliveryException e) {
                log.error("[DeactivateAccount] falha ao avisar professional={} por e-mail: {}", professional.getId(), e.getMessage());
            }
        }
        log.info("[DeactivateAccount] establishment={} DEACTIVATED", establishment.getId());
    }
}
