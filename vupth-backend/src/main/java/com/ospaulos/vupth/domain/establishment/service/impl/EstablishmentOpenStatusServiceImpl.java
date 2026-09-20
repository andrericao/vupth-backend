package com.ospaulos.vupth.domain.establishment.service.impl;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.model.EstablishmentAddress;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import java.time.LocalDate;
import java.time.LocalTime;
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
    private final ProfessionalRepository professionalRepository;

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

    /**
     * S1 (fatia 2) — {@code aberto ⇔ ∃ prof afiliado em {WORKING, ON_BREAK}}. Ver contrato completo
     * (regras do modelo hibrido) na interface. Mesma transacao do chamador — recalculo atomico.
     */
    @Override
    public boolean deriveFromProfessionals(UUID establishmentId) {
        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow();

        // Query direta (nao a colecao lazy inversa — stale quando a afiliacao muda na mesma tx).
        boolean anyoneIn = professionalRepository.existsByEstablishment_IdAndWorkStatusIn(
                establishmentId,
                List.of(ProfessionalStatus.WORKING, ProfessionalStatus.ON_BREAK));
        OpenStatus derived = anyoneIn ? OpenStatus.OPEN : OpenStatus.CLOSED;

        OpenStatus current = establishment.getOpenStatus();
        if (derived == current) {
            return false;
        }
        // CLOSED derivado nao rebaixa um TEMPORARILY_CLOSED manual (ja esta fechado).
        if (derived == OpenStatus.CLOSED && current == OpenStatus.TEMPORARILY_CLOSED) {
            return false;
        }

        establishment.setOpenStatus(derived);
        establishment.setLastStatusChange(ZonedDateTime.now());
        if (derived == OpenStatus.OPEN) {
            recordRealOpening(establishment);
        }
        establishmentRepository.save(establishment);
        log.info("[OpenStatus] derivado — establishment={} {} -> {}", establishmentId, current, derived);
        return true;
    }

    /**
     * O dia passa a valer a hora em que a loja REALMENTE abriu (decisao do Andre, 19/09).
     *
     * <p>Quem abre a loja e a chegada do primeiro profissional (D11), entao o {@code opens} de HOJE
     * vira a hora em que esse profissional marcou "trabalhando" — cadastrado 08:00 e o primeiro chega
     * 07:30, aquele dia abriu 07:30. Nao ha registro novo: escreve-se a <b>excecao por data</b> que o
     * horario por dia (#2) ja tem, entao o que o cliente le em {@code opening_time} ja e o horario
     * real, sem campo novo no contrato.</p>
     *
     * <p><b>Escreve uma vez por dia, e so quando o dia ainda segue a regra da semana.</b> Se ja existe
     * excecao para hoje, nao toca: ou foi a chegada de hoje (a primeira manda — a loja que fecha ao
     * meio-dia e reabre as 14h continua tendo aberto de manha), ou foi o dono que marcou o dia na mao,
     * e a mao manda. O {@code closes} do plano e preservado: ele e quem guia o auto-fechamento.</p>
     *
     * <p><b>Nao escreve</b> quando o horario nao esta configurado (qualquer hora vale — nao ha janela a
     * estreitar), quando hoje e dia fechado ou 24h no plano (nao ha abertura a corrigir), e quando a
     * chegada e DEPOIS do fechamento do dia: ai a janela viraria {@code closes < opens}, que o dominio
     * le como expediente que vira a meia-noite, e o auto-fechamento passaria a mirar amanha.</p>
     */
    private void recordRealOpening(Establishment establishment) {
        BusinessHours hours = establishment.getBusinessHours();
        if (!hours.isConfigured()) {
            return;
        }
        LocalDate today = BusinessClock.today();
        if (hours.hasExceptionOn(today)) {
            return;
        }
        DayHours plano = hours.on(today);
        if (plano == null || plano.closed() || plano.isAllDay()) {
            return;
        }
        LocalTime agora = BusinessClock.nowTime().withSecond(0).withNano(0);
        if (!agora.isBefore(plano.closes())) {
            return;
        }
        establishment.applyBusinessHours(
                hours.withException(today, DayHours.open(agora, plano.closes()), today));
        log.info("[OpenStatus] abertura real — establishment={} dia={} plano={} real={}",
                establishment.getId(), today, plano.opens(), agora);
    }

    /*
    TODO: verificar metodo de updateStatus
     */
}