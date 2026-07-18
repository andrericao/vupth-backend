package com.ospaulos.vupth.domain.establishment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.api.establishment.dto.response.ProfessionalWithQueueResponse;
import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentWithQueueUseCase;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentHasActiveAttendancesException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentHoursRequiredException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotReadyException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentService;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.owner.repository.OwnerRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class EstablishmentServiceImpl implements EstablishmentService {

    private final OwnerRepository ownerRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ObjectMapper mapper;
    private final GetEstablishmentWithQueueUseCase getWithQueueUseCase;
    private final AttendanceRepository attendanceRepository;

    @Override
    public EstablishmentResponse registerEstablishment(EstablishmentRequest request){

        log.info("SERVICE CREATING ESTABLISHMENT " + request.name() );

        Owner owner = ownerRepository.findById(request.ownerId()).orElseThrow();

        Establishment establishment = new Establishment();

        establishment.setServices(request.services());
        establishment.setName(request.name());
        establishment.setUrlImage(request.urlImage());
        establishment.setServiceType(request.serviceType());
        establishment.setStatus(request.status());
        establishment.setOpenStatus(request.openStatus());
        establishment.setOwner(owner);

        Establishment establishmentCreated = this.establishmentRepository.save(establishment);

        return EstablishmentResponse.fromEntity(establishmentCreated);
    }

    @Override
    public List<EstablishmentResponse> findAll() {
        return establishmentRepository.findAll().stream()
                .map(EstablishmentResponse::fromEntity)
                .toList();
    }

    @Override
    public List<EstablishmentResponse> findAllActive() {
        log.info("[Establishment] findAllActive");
        return toResponsesSortedByWait(establishmentRepository.findAllActive());
    }

    @Override
    public List<EstablishmentResponse> searchByName(String name) {
        log.info("[Establishment] searchByName — name={}", name);
        return toResponsesSortedByWait(
                establishmentRepository.findByNameContainingIgnoreCaseAndStatusNot(name));
    }

    @Override
    public List<EstablishmentResponse> findByServiceType(ServiceType serviceType) {
        log.info("[Establishment] findByServiceType — type={}", serviceType);
        return toResponsesSortedByWait(establishmentRepository.findActiveByServiceType(serviceType));
    }

    @Override
    public List<EstablishmentResponse> searchByNeighborhood(String neighborhood) {
        log.info("[Establishment] searchByNeighborhood — neighborhood={}", neighborhood);
        return toResponsesSortedByWait(establishmentRepository.findActiveByNeighborhood(neighborhood));
    }

    @Override
    public List<EstablishmentResponse> searchByServiceTypeAndNeighborhood(ServiceType serviceType, String neighborhood) {
        log.info("[Establishment] searchByServiceTypeAndNeighborhood — type={} neighborhood={}",
                serviceType, neighborhood);
        return toResponsesSortedByWait(
                establishmentRepository.findActiveByServiceTypeAndNeighborhood(serviceType, neighborhood));
    }

    /**
     * Mapeia os establishments para a resposta enriquecida com o menor tempo de espera
     * (min entre os profissionais com servico) e ordena por menor fila (asc, nulls last).
     *
     * @apiNote Calcula a fila por establishment (reusa o snapshot da fila), o que gera
     *          varias queries em listas grandes. Aceitavel no MVP — ver backlog de performance.
     */
    private List<EstablishmentResponse> toResponsesSortedByWait(List<Establishment> establishments) {
        return establishments.stream()
                .map(e -> {
                    QueueSummary summary = queueSummary(e.getId());
                    return EstablishmentResponse.fromEntity(
                            e, summary.minWaitMinutes(), summary.availableProfessionals());
                })
                .sorted(Comparator.comparing(EstablishmentResponse::estimatedWaitMinutes,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Resumo da fila usado pela listagem publica: menor tempo de espera + quantos
     * profissionais afiliados COM servico a loja tem. Ambos derivam do mesmo snapshot
     * ({@code professionals-with-queue}, que ja filtra profissional sem servico — #31),
     * entao uma unica chamada do use case alimenta os dois campos.
     */
    private QueueSummary queueSummary(UUID establishmentId) {
        try {
            List<ProfessionalWithQueueResponse> professionals =
                    getWithQueueUseCase.execute(establishmentId).professionals();
            Integer minWait = professionals.stream()
                    .map(ProfessionalWithQueueResponse::estimatedWaitMinutes)
                    .min(Integer::compareTo)
                    .orElse(null);
            return new QueueSummary(minWait, professionals.size());
        } catch (Exception e) {
            log.warn("[Establishment] queueSummary falhou para {}: {}", establishmentId, e.getMessage());
            return new QueueSummary(null, 0);
        }
    }

    /** Menor tempo de espera (min, null se nenhum) + nº de profissionais com servico da loja. */
    private record QueueSummary(Integer minWaitMinutes, int availableProfessionals) {}

    @Override
    public EstablishmentResponse updateOpenStatus(UUID establishmentId, UUID requesterId, OpenStatus newStatus) {
        log.info("[Establishment] updateOpenStatus — id={} requester={} newStatus={}",
                establishmentId, requesterId, newStatus);

        Establishment establishment = establishmentRepository
                .findById(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        // Acesso: dono da loja OU profissional afiliado a ela (S/BUG — funcionario tambem abre/fecha).
        if (!canManageOpenStatus(establishment, requesterId)) {
            throw new EstablishmentAccessDeniedException(requesterId, establishmentId);
        }

        // Gate de abertura (só ao virar OPEN):
        if (newStatus == OpenStatus.OPEN) {
            // (#31) ao menos um profissional com ao menos um serviço.
            if (!hasProfessionalWithService(establishment)) {
                throw new EstablishmentNotReadyException(establishmentId);
            }
            // (#11g2c) horário de funcionamento definido (abertura + fechamento).
            if (establishment.getOpeningTime() == null || establishment.getClosingTime() == null) {
                throw new EstablishmentHoursRequiredException(establishmentId);
            }
        }

        // Gate de fechamento (Partner BUG6): não fechar com cliente na fila ou em atendimento.
        // Só bloqueia a transição OPEN -> fechado (idempotência: re-fechar um já fechado não valida).
        if ((newStatus == OpenStatus.CLOSED || newStatus == OpenStatus.TEMPORARILY_CLOSED)
                && establishment.getOpenStatus() == OpenStatus.OPEN
                && attendanceRepository.countActiveByEstablishment(establishmentId) > 0) {
            throw new EstablishmentHasActiveAttendancesException(establishmentId);
        }

        establishment.setOpenStatus(newStatus);
        establishment.setLastStatusChange(ZonedDateTime.now());
        Establishment saved = establishmentRepository.save(establishment);

        return EstablishmentResponse.fromEntity(saved);
    }

    /**
     * Pode alterar o open-status quem é o dono da loja OU um profissional afiliado a ela.
     * (Profissional-funcionário também abre/fecha a loja — decisão de produto do Andre.)
     */
    private boolean canManageOpenStatus(Establishment establishment, UUID requesterId) {
        boolean isOwner = establishment.getOwner() != null
                && establishment.getOwner().getId().equals(requesterId);
        boolean isAffiliatedProfessional = establishment.getProfessionals().stream()
                .anyMatch(prof -> prof.getId().equals(requesterId));
        return isOwner || isAffiliatedProfessional;
    }

    /**
     * Establishment é considerado "ativo o suficiente para abrir" quando tem
     * ao menos um profissional vinculado com ao menos um serviço cadastrado.
     */
    private boolean hasProfessionalWithService(Establishment establishment) {
        return establishment.getProfessionals().stream()
                .anyMatch(prof -> prof.getServices() != null && !prof.getServices().isEmpty());
    }

    @Override
    public EstablishmentResponse findById(UUID id){
         Establishment establishment = establishmentRepository.findByIdEager(id)
                .orElseThrow(() -> new EstablishmentNotFoundException(id));

         return EstablishmentResponse.fromEntity(establishment);
    }

    @Override
    public List<ServiceItem> getServicesItemByEstablishment(UUID establishmentId){

        Establishment establishment = establishmentRepository.findByIdEager(establishmentId)
                .orElseThrow(() -> new EntityNotFoundException("Establishment not found"));

        return establishment.getServicesItemByEstablishment();
    }
}
