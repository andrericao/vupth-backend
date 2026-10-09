package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Os tres portoes de "compromisso vivo com terceiros", os mesmos do apagar ({@link DeleteAccountUseCase}), agora para desativar a conta
 * (desativar-conta, decisao (a)): cliente numa fila ou com intencao, profissional com atendimento,
 * loja com fila — 409 ACCOUNT_DEACTIVATE_NOT_ALLOWED,
 * com a mensagem dizendo o que fazer para destravar.
 */
@Component
@RequiredArgsConstructor
public class AccountCommitmentGate {

    /**
     * Compromissos vivos. {@code INTENTION} entra: seria PROMOVIDA a {@code SCHEDULED} pelo timer —
     * uma conta fora de uso com intencao pendente viraria fantasma na fila de alguem.
     */
    static final List<AttendanceStatus> ACTIVE_STATUSES = List.of(
            AttendanceStatus.SCHEDULED,
            AttendanceStatus.IN_PROGRESS,
            AttendanceStatus.PAUSED,
            AttendanceStatus.LATE,
            AttendanceStatus.INTENTION);

    public enum Blocker {
        CLIENT_IN_QUEUE("You are still in a queue. Leave the queue (or wait to be served) before deactivating your account."),
        PROFESSIONAL_WITH_ATTENDANCES("You still have active attendances. Finish or cancel them before deactivating your account."),
        ESTABLISHMENT_WITH_QUEUE("Your establishment still has clients in the queue. Wait until it is empty before deactivating your account.");

        private final String message;

        Blocker(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final AttendanceRepository attendanceRepository;
    private final EstablishmentRepository establishmentRepository;

    /** O primeiro compromisso vivo que impede a conta de sair de uso, ou vazio. */
    public Optional<Blocker> firstBlocker(BaseUser user) {
        if (user.hasClientProfile()
                && attendanceRepository.existsByClient_IdAndStatusIn(user.getId(), ACTIVE_STATUSES)) {
            return Optional.of(Blocker.CLIENT_IN_QUEUE);
        }
        if (user.hasProfessionalProfile()
                && attendanceRepository.existsByProfessional_IdAndStatusIn(user.getId(), ACTIVE_STATUSES)) {
            return Optional.of(Blocker.PROFESSIONAL_WITH_ATTENDANCES);
        }
        if (user.hasOwnerProfile()) {
            for (Establishment establishment : establishmentRepository.findByOwnerId(user.getId())) {
                if (attendanceRepository.existsByEstablishment_IdAndStatusIn(establishment.getId(), ACTIVE_STATUSES)) {
                    return Optional.of(Blocker.ESTABLISHMENT_WITH_QUEUE);
                }
            }
        }
        return Optional.empty();
    }
}
