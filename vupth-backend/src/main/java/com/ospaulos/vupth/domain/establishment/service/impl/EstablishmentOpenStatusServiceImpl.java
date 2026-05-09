package com.ospaulos.vupth.domain.establishment.service.impl;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.model.EstablishmentAddress;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EstablishmentOpenStatusServiceImpl implements EstablishmentOpenStatusService {

    private final EstablishmentRepository establishmentRepository;

    /**
     * Atualiza o status de funcionamento do establishment
     */
    @Override
    public void updateEstablishmentOpenStatus(UUID establishmentId, OpenStatus newStatus) {
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(/*() -> new ResourceNotFoundException("Establishment not found")*/);

        // Record the status change
        establishment.setOpenStatus(newStatus);
        establishment.setLastStatusChange(ZonedDateTime.now());

        establishmentRepository.save(establishment);
        log.info("Status do establishment {} atualizado para: {}", establishmentId, newStatus);
    }

    /**
     * Checks whether the establishment is open based on business hours
     */
    @Override
    public boolean isEstablishmentOpen(UUID establishmentId) {
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(/*() -> new ResourceNotFoundException("Establishment not found")*/);

        // Se o status atual for CLOSED ou TEMPORARILY_CLOSED, retorna falso
        if (establishment.getOpenStatus() == OpenStatus.CLOSED ||
                establishment.getOpenStatus() == OpenStatus.TEMPORARILY_CLOSED) {
            return false;
        }

        // Get the main address of the establishment
        Optional<EstablishmentAddress> mainAddress = establishment.getAddresses().stream()
                .filter(EstablishmentAddress::getIsMain)
                .findFirst();

        if (mainAddress.isEmpty()) {
            log.warn("Estabelecimento {} has no main address", establishment.getId());
            return false;
        }

        return true;//isWithinBusinessHours(mainAddress.get().getBusinessHours());
    }

     /**
     * Checks whether the current time is within business hours
     */
    /*
    @Override
    private boolean isWithinBusinessHours(String businessHours) {
        if (StringUtils.isBlank(businessHours)) {
            return false;
        }

        try {
            LocalTime currentTime = LocalTime.now();
            DayOfWeek currentDay = LocalDate.now().getDayOfWeek();

            // Assumes businessHours is in the format:
            // "SEG-SEX:09:00-18:00;SAB:09:00-13:00"
            BusinessHoursParser parser = new BusinessHoursParser(businessHours);
            return parser.isOpenAt(currentDay, currentTime);
        } catch (Exception e) {
            log.error("Error checking opening hours: {}", e.getMessage());
            return false;
        }
    }*/

    /**
     * Automatically updates establishment status based on business hours
     */
    @Override
    @Scheduled(cron = "0 */15 * * * *") // Executa a cada 15 minutos
    public void updateAllEstablishmentsStatus() {
        log.info("Starting automatic establishment status update");

        List<Establishment> establishments = establishmentRepository.findAll();

        for (Establishment establishment : establishments) {
            try {

                boolean isOpen = isEstablishmentOpen(establishment.getId());
                OpenStatus newStatus = isOpen ? OpenStatus.OPEN : OpenStatus.CLOSED;

                if (establishment.getOpenStatus() != newStatus) {
                    updateEstablishmentOpenStatus(establishment.getId(), newStatus);
                }
            } catch (Exception e) {
                log.error("Erro ao atualizar status do establishment {}: {}",
                        establishment.getId(), e.getMessage());
            }
        }
    }

    /*
    TODO: verificar metodo de updateStatus
     */
}