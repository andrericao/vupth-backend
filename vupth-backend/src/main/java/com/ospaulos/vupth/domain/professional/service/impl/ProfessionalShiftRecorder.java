package com.ospaulos.vupth.domain.professional.service.impl;

import com.ospaulos.vupth.domain.professional.event.WorkStatusChangedEvent;
import com.ospaulos.vupth.domain.professional.model.ProfessionalShift;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Grava o expediente real (Partner S4): abre o turno na entrada em expediente e fecha na volta a
 * Fora. Sincrono, na transacao de quem mudou o status — o turno e a mudanca sobem ou caem juntos.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfessionalShiftRecorder {

    private final ProfessionalShiftRepository shiftRepository;

    @EventListener
    public void on(WorkStatusChangedEvent event) {
        if (event.startsShift()) {
            if (!shiftRepository.findByProfessionalIdAndEndedAtIsNull(event.professionalId()).isEmpty()) {
                return; // ja ha turno aberto — nao duplica
            }
            shiftRepository.save(new ProfessionalShift(
                    event.professionalId(), event.establishmentId(), event.at()));
            log.info("[Shift] inicio — professional={} at={}", event.professionalId(), event.at());
        } else if (event.endsShift()) {
            List<ProfessionalShift> open = shiftRepository.findByProfessionalIdAndEndedAtIsNull(event.professionalId());
            open.forEach(shift -> shift.end(event.at()));
            shiftRepository.saveAll(open);
            log.info("[Shift] fim — professional={} at={} turnos={}", event.professionalId(), event.at(), open.size());
        }
    }
}
