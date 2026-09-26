package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.api.professional.usecase.LeaveEstablishmentUseCase;
import com.ospaulos.vupth.api.user.exception.AccountDeleteNotAllowedException;
import com.ospaulos.vupth.api.user.exception.InvalidCurrentPasswordException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.refreshToken.repository.RefreshTokenRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.PasswordResetCodeRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Apagar a conta (Partner S19 / LGPD / exigencia de publicacao nas lojas de app).
 *
 * <h3>O modelo: apaga o titular, nao as relacoes dele</h3>
 * Um atendimento tem TRES envolvidos — cliente, profissional e estabelecimento — e cada um tem
 * direito ao proprio historico. Se apagar a conta do cliente levasse o atendimento junto, o
 * profissional perderia faturamento que ele tambem produziu. Por isso o que se apaga aqui e o
 * <b>dado pessoal do titular</b>; a linha sobrevive anonimizada, e o outro lado continua vendo o
 * atendimento — agora atribuido a "Usuario removido". E o padrao do mercado para registro
 * transacional (LGPD art. 16 / GDPR art. 17.3: dado anonimizado deixa de ser dado pessoal).
 *
 * <h3>Ordem das etapas (importa)</h3>
 * <ol>
 *   <li><b>Senha</b> — 401 se nao conferir. Nada e tocado antes disso.</li>
 *   <li><b>Portoes 409</b> — TODOS antes de qualquer escrita, para que a recusa nunca deixe a
 *       conta meio desmontada.</li>
 *   <li><b>Desmonte</b> — reusa os casos de uso que ja existem
 *       ({@link LeaveEstablishmentUseCase}), que revalidam as proprias regras e recalculam o
 *       open status da loja.</li>
 *   <li><b>Apagar o que e so dele</b> — enderecos, device tokens, refresh tokens, codigos de
 *       reset e os papeis.</li>
 *   <li><b>Anonimizar</b> o {@code base_user}.</li>
 * </ol>
 *
 * <h3>Por que os papeis saem por ultimo, e por que isso mata o token vivo</h3>
 * O {@code JwtAuthenticationConverter} carrega o usuario do banco a CADA request. Sem papeis e
 * com {@code is_active = false}, o access token que ja estava na mao do app morre no request
 * seguinte — nao ha janela de ate 1h esperando o token expirar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeleteAccountUseCase {

    /**
     * Compromissos vivos com terceiros. {@code INTENTION} entra: ela ainda nao esta na fila, mas
     * seria PROMOVIDA a {@code SCHEDULED} pelo timer do #4 — uma conta anonimizada com intencao
     * pendente viraria fantasma na fila de alguem.
     */
    private static final List<AttendanceStatus> ACTIVE_STATUSES = List.of(
            AttendanceStatus.SCHEDULED,
            AttendanceStatus.IN_PROGRESS,
            AttendanceStatus.PAUSED,
            AttendanceStatus.LATE,
            AttendanceStatus.INTENTION);

    private final UserRepository userRepository;
    private final AttendanceRepository attendanceRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetCodeRepository passwordResetCodeRepository;
    private final LeaveEstablishmentUseCase leaveEstablishmentUseCase;
    private final PasswordEncoder passwordEncoder;
    private final com.ospaulos.vupth.domain.user.service.ProfilePhotoService profilePhotoService;

    @Transactional
    public void execute(UUID userId, String currentPassword) {
        log.info("[DeleteAccount] execute — user={}", userId);

        BaseUser user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new InvalidCurrentPasswordException();
        }

        assertNoActiveCommitments(user);

        detachOwnedEstablishments(user);
        detachProfessional(user);
        deleteOwnedOnlyData(user);

        // A foto e dado pessoal: sai do storage, nao so da coluna (a URL publica continuaria abrindo).
        profilePhotoService.deleteQuietly(user.getProfileImageUrl());
        user.anonymize(passwordEncoder.encode(UUID.randomUUID().toString()));
        userRepository.save(user);

        log.info("[DeleteAccount] user={} anonymized — history preserved for the other parties", userId);
    }

    // ───────────────────────────────────────────
    // 2) Portoes — tudo antes da primeira escrita
    // ───────────────────────────────────────────

    private void assertNoActiveCommitments(BaseUser user) {
        if (user.hasClientProfile()
                && attendanceRepository.existsByClient_IdAndStatusIn(user.getId(), ACTIVE_STATUSES)) {
            throw AccountDeleteNotAllowedException.clientInQueue();
        }

        if (user.hasProfessionalProfile()
                && attendanceRepository.existsByProfessional_IdAndStatusIn(user.getId(), ACTIVE_STATUSES)) {
            throw AccountDeleteNotAllowedException.professionalWithActiveAttendances();
        }

        if (user.hasOwnerProfile()) {
            for (Establishment establishment : establishmentRepository.findByOwnerId(user.getId())) {
                if (attendanceRepository.existsByEstablishment_IdAndStatusIn(
                        establishment.getId(), ACTIVE_STATUSES)) {
                    throw AccountDeleteNotAllowedException.establishmentWithActiveQueue();
                }
            }
        }
    }

    // ───────────────────────────────────────────
    // 3) Desmonte — chamando quem ja sabe fazer
    // ───────────────────────────────────────────

    /**
     * A loja NAO e dado pessoal do dono e nao pode ser apagada: os atendimentos dela pertencem
     * tambem aos clientes e aos profissionais. Entao ela e <b>desligada</b> — cada profissional e
     * desafiliado pelo {@link LeaveEstablishmentUseCase} (que revalida atendimento ativo e
     * recalcula o open status), a loja vai a {@code CLOSED} e o vinculo com o dono e desfeito.
     *
     * <p>⚠️ {@code openStatus = CLOSED} <b>NAO</b> tira a loja da vitrine: as queries publicas
     * filtram por {@code status <> INACTIVE} ({@code findAllActive} e as irmas dela), que e outro
     * campo — {@code openStatus} e so "esta aberta AGORA". Sem {@code status = INACTIVE} a loja
     * orfa continuaria listada, e uma loja sem dono na lista derrubava a vitrine inteira com 500
     * (o mapper fazia {@code getOwner().getId()} cru). Por isso os dois campos mudam aqui, e o
     * mapper virou null-safe.</p>
     */
    private void detachOwnedEstablishments(BaseUser user) {
        if (!user.hasOwnerProfile()) {
            return;
        }

        for (Establishment establishment : establishmentRepository.findByOwnerId(user.getId())) {
            for (Professional professional : professionalRepository.findByEstablishmentId(establishment.getId())) {
                leaveEstablishmentUseCase.execute(professional.getId());
            }

            establishment.setOpenStatus(OpenStatus.CLOSED);
            establishment.setStatus(EstablishmentStatus.INACTIVE);
            establishment.setOwner(null);
            establishmentRepository.save(establishment);

            log.info("[DeleteAccount] establishment={} closed and detached from owner={}",
                    establishment.getId(), user.getId());
        }

        // A colecao em memoria ainda aponta para as lojas; sem limpar, o cascade do save(user)
        // reescreveria o owner que acabamos de desfazer.
        user.getOwner().getEstablishments().clear();
    }

    /**
     * O profissional sai da loja onde trabalhava e para de aparecer como disponivel. Os servicos
     * e os atendimentos dele FICAM — sao o historico do estabelecimento e dos clientes.
     */
    private void detachProfessional(BaseUser user) {
        if (!user.hasProfessionalProfile()) {
            return;
        }

        leaveEstablishmentUseCase.execute(user.getId());

        Professional professional = professionalRepository.findById(user.getId()).orElseThrow();
        professional.setSpecialty(null);
        professional.setIsAvailable(false);
        professional.setAcceptingNew(false);
        professional.setWorkStatus(ProfessionalStatus.OFF);
        professionalRepository.save(professional);
    }

    // ───────────────────────────────────────────
    // 4) O que e SO do titular some de verdade
    // ───────────────────────────────────────────

    private void deleteOwnedOnlyData(BaseUser user) {
        deviceTokenRepository.deleteByUserId(user.getId());
        refreshTokenRepository.deleteByUserId(user.getId());
        passwordResetCodeRepository.deleteByUserId(user.getId());

        // orphanRemoval = true nas duas colecoes: limpar a lista APAGA as linhas.
        user.getUserAddresses().clear();
        user.getRoles().clear();
    }
}
