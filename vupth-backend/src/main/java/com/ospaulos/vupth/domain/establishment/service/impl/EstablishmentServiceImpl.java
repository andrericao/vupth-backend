package com.ospaulos.vupth.domain.establishment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ospaulos.vupth.api.address.dto.response.AddressResponse;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentsQueueSummaryUseCase;
import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentsQueueSummaryUseCase.QueueSummary;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentHasActiveAttendancesException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentHoursRequiredException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotReadyException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.model.EstablishmentAddress;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentService;
import com.ospaulos.vupth.domain.address.valueObject.Haversine;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.GeoSearch;
import com.ospaulos.vupth.domain.establishment.valueObject.LocationFilter;
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
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class EstablishmentServiceImpl implements EstablishmentService {

    private final OwnerRepository ownerRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ObjectMapper mapper;
    private final GetEstablishmentsQueueSummaryUseCase queueSummaryUseCase;
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

    /**
     * <b>Precedencia do {@code name} (backlog #29):</b> o escopo de cidade/UF <b>nao</b> se aplica
     * quando a busca veio com nome — exatamente como {@code serviceType} e {@code neighborhood} ja
     * nao se aplicam. Nao e economia de codigo, e a mesma regra de produto que faz o
     * {@code radiusKm} sem origem ser <b>ignorado sem erro</b>: {@code city}/{@code uf} sao um
     * escopo que o <b>app</b> preenche sozinho (a localidade do aparelho), enquanto o nome foi
     * <b>digitado pela pessoa</b>. Cruzar os dois faria quem procura uma loja pelo nome receber
     * lista vazia por causa de um escopo que ela nao escolheu nem ve na tela — e "nao existe" e
     * "existe, mas nao no seu estado" sao indistinguiveis do lado de fora.
     */
    @Override
    public List<EstablishmentResponse> searchPublic(String name, ServiceType serviceType,
                                                    String neighborhood, LocationFilter location,
                                                    GeoSearch geo) {
        log.info("[Establishment] searchPublic — name={} type={} neighborhood={} location={} geo={}",
                name, serviceType, neighborhood, location, geo);

        LocationFilter scope = hasText(name) ? null : location;

        List<Establishment> found = inLocation(findByFilters(name, serviceType, neighborhood), scope);

        // B10: UM resumo para a lista inteira. Antes era uma chamada ao use case de DETALHE por
        // loja — ver GetEstablishmentsQueueSummaryUseCase para o que mudou e o que foi preservado.
        Map<UUID, QueueSummary> summaries = queueSummaryUseCase.execute(
                found.stream().map(Establishment::getId).toList());

        List<EstablishmentResponse> responses = found.stream()
                .map(e -> {
                    QueueSummary summary = summaries.getOrDefault(e.getId(), QueueSummary.EMPTY);
                    return EstablishmentResponse.fromEntity(
                            e, summary.minWaitMinutes(), summary.availableProfessionals());
                })
                .toList();

        return geo == null ? sortedByWait(responses) : sortedByDistance(responses, geo);
    }

    /**
     * Aplica o escopo de cidade/UF sobre o que a query trouxe. Roda em Java, e nao em mais um
     * {@code @Query}: com {@code city} e {@code uf} somados aos filtros que ja existiam, a cascata
     * de queries por combinacao passaria de 5 para 16 — e a comparacao sem acento, que e o ponto
     * do filtro, exigiria a extensao {@code unaccent} ou uma coluna normalizada. Aqui a carga extra
     * e nula: as lojas ja estao na memoria e o endereco ja e lido no mapeamento
     * ({@code getMainAddress}).
     *
     * <p>Casa com <b>qualquer</b> endereco da loja, nao so o principal — e o mesmo alcance do
     * {@code ?neighborhood=}, que faz {@code JOIN e.addresses}.</p>
     */
    private List<Establishment> inLocation(List<Establishment> found, LocationFilter location) {
        if (location == null) {
            return found;
        }
        return found.stream()
                .filter(e -> e.getAddresses().stream()
                        .map(EstablishmentAddress::getAddress)
                        .anyMatch(location::matches))
                .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Escolhe a query pelo filtro presente. O {@code name} tem prioridade sobre os demais — quem
     * digitou o nome da loja quer aquela loja, nao o cruzamento com categoria/bairro.
     */
    private List<Establishment> findByFilters(String name, ServiceType serviceType, String neighborhood) {
        boolean hasName = hasText(name);
        boolean hasNeighborhood = hasText(neighborhood);

        if (hasName) {
            return establishmentRepository.findByNameContainingIgnoreCaseAndStatusNot(name);
        }
        if (serviceType != null && hasNeighborhood) {
            return establishmentRepository.findActiveByServiceTypeAndNeighborhood(serviceType, neighborhood);
        }
        if (hasNeighborhood) {
            return establishmentRepository.findActiveByNeighborhood(neighborhood);
        }
        if (serviceType != null) {
            return establishmentRepository.findActiveByServiceType(serviceType);
        }
        return establishmentRepository.findAllActive();
    }

    /**
     * Ordenacao padrao da listagem: menor tempo de espera primeiro (asc, nulls last). E o que vale
     * quando a busca nao mandou coordenada — inclusive quando o usuario NEGOU a permissao de
     * localizacao, caso em que a lista tem de continuar util em vez de vir vazia.
     *
     * @apiNote O tempo de espera vem de uma query por establishment (reusa o snapshot da fila), o
     *          que gera varias queries em listas grandes. Aceitavel no MVP — ver backlog de
     *          performance. E tambem o motivo de a distancia ser calculada aqui e nao em SQL:
     *          filtrar o raio no banco pouparia uma fracao irrelevante perto deste N+1.
     */
    private List<EstablishmentResponse> sortedByWait(List<EstablishmentResponse> responses) {
        return responses.stream()
                .sorted(Comparator.comparing(EstablishmentResponse::estimatedWaitMinutes,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Ordenacao por proximidade (backlog #2, fatia B). Tres regras de produto moram aqui:
     *
     * <ol>
     *   <li><b>Sem raio, ninguem some.</b> Sem {@code radiusKm} a lista devolve TUDO, so reordenado
     *       — a loja sem coordenada vai para o FIM com {@code distance_km: null} em vez de sumir.
     *       Um raio default deixaria a tela vazia para quem esta numa regiao sem loja, e o usuario
     *       nao distingue isso de "o app quebrou".</li>
     *   <li><b>Com raio, a loja sem coordenada sai</b> — pediram explicitamente "ate X km", e nao
     *       da para afirmar que uma loja sem ponto no mapa cabe nisso.</li>
     *   <li><b>Ordem ESTAVEL:</b> distancia e, no empate, {@code id}. Sem o desempate, duas lojas
     *       equidistantes trocariam de lugar entre requests e a paginacao (backlog) repetiria ou
     *       pularia item.</li>
     * </ol>
     *
     * <p><b>O desempate compara o id como TEXTO, nao como {@link java.util.UUID}, de proposito.</b>
     * {@code UUID.compareTo} compara os dois metades como {@code long} <b>com sinal</b>, entao um id
     * que comeca em {@code 8}-{@code f} (bit alto ligado = long negativo) ordena ANTES de um que
     * comeca em {@code 0}-{@code 7} — o oposto do que qualquer um espera lendo o hexadecimal. O
     * Postgres compara {@code uuid} byte a byte, <b>sem sinal</b>. Usar o texto faz esta ordenacao
     * bater com a que o {@code ORDER BY distancia, id} vai produzir quando ela migrar para SQL junto
     * com a paginacao; com {@code UUID.compareTo}, a lista mudaria de ordem no dia da migracao.</p>
     *
     * <p>O filtro do raio usa a distancia CRUA e so o valor exposto e arredondado: arredondar antes
     * deixaria entrar uma loja a 10,004km num raio de 10km.</p>
     */
    private List<EstablishmentResponse> sortedByDistance(List<EstablishmentResponse> responses, GeoSearch geo) {
        return responses.stream()
                .map(r -> new Located(r, distanceKm(r, geo)))
                .filter(l -> !geo.hasRadius() || (l.distanceKm != null && l.distanceKm <= geo.radiusKm()))
                .sorted(Comparator.comparing((Located l) -> l.distanceKm,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(l -> l.response.id().toString()))
                .map(l -> l.response.withDistanceKm(
                        l.distanceKm == null ? null : Haversine.roundKm(l.distanceKm)))
                .toList();
    }

    /** Distancia crua ate a loja, ou {@code null} quando ela nao tem coordenada resolvida ainda. */
    private Double distanceKm(EstablishmentResponse response, GeoSearch geo) {
        AddressResponse address = response.address();
        if (address == null || address.latitude() == null || address.longitude() == null) {
            return null;
        }
        return Haversine.distanceKm(
                geo.latitude(), geo.longitude(), address.latitude(), address.longitude());
    }

    /** Par temporario resposta+distancia crua, para filtrar e ordenar antes de arredondar. */
    private record Located(EstablishmentResponse response, Double distanceKm) {}

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
