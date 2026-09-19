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
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentSort;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.establishment.valueObject.Showcase;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.owner.repository.OwnerRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
    private final ProfessionalRepository professionalRepository;

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
    public List<EstablishmentResponse> findMine(UUID ownerId) {
        return establishmentRepository.findByOwnerId(ownerId).stream()
                .map(EstablishmentResponse::fromEntity)
                .toList();
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
                                                    GeoSearch geo, Showcase showcase) {
        log.info("[Establishment] searchPublic — name={} type={} neighborhood={} location={} geo={} showcase={}",
                name, serviceType, neighborhood, location, geo, showcase);

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

        return sortedShowcase(responses.stream().filter(r -> accepts(r, showcase)).toList(), geo, showcase);
    }

    /**
     * Os filtros de <b>prontidao</b> da vitrine (06/09). Rodam em Java, e nao em SQL, pelo mesmo
     * motivo do {@link #inLocation}: os tres campos que eles olham — {@code is_open} efetivo,
     * {@code available_professionals} e {@code estimated_wait_minutes} — <b>nao existem no banco</b>.
     * Os dois ultimos vem do resumo de fila (B10) e o primeiro cruza {@code open_status} com o
     * relogio de parede da loja; filtrar antes exigiria recalcular tudo isso em SQL, criando uma
     * <b>segunda fonte</b> para numeros que o produto ja mostra na tela.
     */
    private boolean accepts(EstablishmentResponse r, Showcase showcase) {
        if (showcase.openNow() && !r.isOpen()) {
            return false;
        }
        if (showcase.hasAvailability()
                && (r.availableProfessionals() == null || r.availableProfessionals() <= 0)) {
            return false;
        }
        // Espera null = loja sem profissional com servico. Sai: "desconhecida" nao cabe em "ate N".
        return showcase.maxWaitMinutes() == null
                || (r.estimatedWaitMinutes() != null
                        && r.estimatedWaitMinutes() <= showcase.maxWaitMinutes());
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
     * Ordena a pagina inteira e so entao arredonda a distancia exposta.
     *
     * <p><b>A ordem tem tres degraus, nesta ordem:</b></p>
     * <ol>
     *   <li><b>Aberta antes de fechada</b> — ver {@link #openFirst()}. Vale <b>sempre</b>, inclusive
     *       com {@code sort} explicito (decisao do Andre, 06/09): o {@code sort} ordena
     *       <b>dentro</b> de cada bloco. Sem isso, {@code sort=wait} poria no topo a loja fechada,
     *       que tem fila vazia e portanto a menor espera possivel;</li>
     *   <li>o criterio pedido — ver {@link #within};</li>
     *   <li><b>{@code id} como desempate</b>, em <b>todas</b> as ordenacoes. Sem ele, duas lojas
     *       empatadas (mesma espera, mesma distancia arredondada) trocariam de lugar entre requests
     *       e a paginacao (backlog #18) repetiria ou pularia item.</li>
     * </ol>
     *
     * <p><b>O raio (e so ele) filtra aqui</b>, e pela distancia <b>crua</b>: arredondar antes
     * deixaria entrar uma loja a 10,004 km num raio de 10 km. Sem {@code radiusKm} ninguem some —
     * a loja ainda sem coordenada vai para o FIM com {@code distance_km: null} em vez de sumir, e a
     * tela nunca vem vazia para quem esta numa regiao sem loja geocodificada.</p>
     *
     * @apiNote O tempo de espera da pagina inteira vem em quatro consultas fixas desde o B10 (ver
     *          {@code GetEstablishmentsQueueSummaryUseCase}); antes era uma chamada ao use case de
     *          detalhe por loja. A distancia continua calculada aqui, e nao em SQL: sao contas
     *          sobre linhas que ja estao na memoria.
     */
    private List<EstablishmentResponse> sortedShowcase(List<EstablishmentResponse> responses,
                                                       GeoSearch geo, Showcase showcase) {
        EstablishmentSort sort = effectiveSort(showcase.sort(), geo);

        return responses.stream()
                .map(r -> new Located(r, geo == null ? null : distanceKm(r, geo)))
                .filter(l -> geo == null || !geo.hasRadius()
                        || (l.distanceKm != null && l.distanceKm <= geo.radiusKm()))
                .sorted(openFirst()
                        .thenComparing(within(sort))
                        .thenComparing(l -> l.response.id().toString()))
                .map(l -> l.response.withDistanceKm(
                        l.distanceKm == null ? null : Haversine.roundKm(l.distanceKm)))
                .toList();
    }

    /**
     * Qual ordenacao vale de fato.
     *
     * <p><b>Sem {@code sort}, nada muda em relacao a antes destes parametros:</b> distancia quando
     * veio coordenada, espera quando nao veio — inclusive quando o usuario NEGOU a permissao de
     * localizacao, caso em que a lista tem de continuar util em vez de vir vazia.</p>
     *
     * <p><b>{@code sort=distance} sem coordenada cai no {@code WAIT} sem erro</b>, pela mesma regra
     * de produto que faz o {@code radiusKm} sem origem ser ignorado: o app pode ter guardado o
     * "mais perto" da sessao passada e chegar aqui sem fix de GPS (permissao concedida e ter
     * coordenada <b>agora</b> sao coisas diferentes). Recusar deixaria a tela vazia por uma escolha
     * que o usuario nao refez. Sai em {@code WARN} porque, se aparecer sempre, e o app pedindo
     * ordem por distancia sem nunca mandar {@code lat}/{@code lng} — isso ai e defeito dele.</p>
     */
    private EstablishmentSort effectiveSort(EstablishmentSort requested, GeoSearch geo) {
        if (requested == null) {
            return geo == null ? EstablishmentSort.WAIT : EstablishmentSort.DISTANCE;
        }
        if (requested == EstablishmentSort.DISTANCE && geo == null) {
            log.warn("[Establishment] sort=distance sem lat/lng — caindo para sort=wait");
            return EstablishmentSort.WAIT;
        }
        return requested;
    }

    /**
     * Primeiro criterio das duas ordenacoes da vitrine: <b>aberta antes de fechada</b>.
     *
     * <p>A listagem devolve loja fechada desde 06/09 (decisao do Andre: quem quer <b>agendar</b>
     * precisa achar a loja que nao esta atendendo agora). Sem este criterio, uma loja fechada
     * chegaria ao <b>topo</b> da Home: com ninguem na fila, o {@code estimated_wait_minutes} dela e
     * <b>0</b> — o menor possivel — e "espera zero" numa loja que nao vai atender e a informacao
     * mais enganosa que a tela pode dar. O mesmo vale para a busca por proximidade: a loja fechada
     * da esquina nao ganha da aberta a dois quarteiroes.</p>
     *
     * <p>Compara o {@code is_open} <b>efetivo</b>, e nao o {@code open_status} persistido: e o
     * mesmo campo que o app usa para escrever "Aberta"/"Fechada" no card, entao a ordem da lista
     * nunca contradiz o rotulo. Uma loja {@code OPEN} cujo horario ja passou aparece como fechada e
     * ordena como fechada.</p>
     */
    private static Comparator<Located> openFirst() {
        return Comparator.comparingInt(l -> l.response.isOpen() ? 0 : 1);
    }

    /**
     * O criterio pedido, aplicado <b>dentro</b> de cada bloco (aberta / fechada).
     *
     * <p>{@code WAIT} e {@code DISTANCE} sao {@code nullsLast}: loja sem disponibilidade (espera
     * {@code null}) e loja ainda sem coordenada ({@code distance_km: null}) vao para o fim da
     * <b>sua</b> metade em vez de sumir — sumir e coisa de filtro explicito
     * ({@code maxWaitMinutes}, {@code radiusKm}), nunca de ordenacao.</p>
     *
     * <p>{@code NAME} usa {@link Collator} pt-BR e nao {@code String.CASE_INSENSITIVE_ORDER}:
     * comparando texto cru, "Ácia" cai <b>depois</b> de "Zulu" (o acento tem code point alto), e
     * numa lista de nomes brasileiros isso aparece na primeira tela.</p>
     */
    private static Comparator<Located> within(EstablishmentSort sort) {
        return switch (sort) {
            case WAIT -> Comparator.comparing(l -> l.response.estimatedWaitMinutes(),
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case DISTANCE -> Comparator.comparing(l -> l.distanceKm,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case NAME -> {
                Collator collator = Collator.getInstance(Locale.forLanguageTag("pt-BR"));
                collator.setStrength(Collator.PRIMARY); // ignora acento e caixa
                yield Comparator.comparing(l -> l.response.name(), collator);
            }
        };
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
            if (!establishment.getBusinessHours().isConfigured()) {
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

        // Decisao do Andre (18/09): loja fechada => ninguem trabalhando. Os dois estados conviviam, e
        // no aparelho a loja aparecia FECHADA com o profissional Trabalhando e a fila aceitando novos.
        // Fechar manda todos para Fora e fecha as filas, na mesma transacao.
        if (newStatus != OpenStatus.OPEN) {
            sendEveryoneHome(saved);
        }

        return EstablishmentResponse.fromEntity(saved);
    }

    /** Fechar a loja leva todos os profissionais a {@code OFF} e fecha as filas (Andre, 18/09). */
    private void sendEveryoneHome(Establishment establishment) {
        List<Professional> inShift = professionalRepository.findByEstablishment_IdAndWorkStatusIn(
                establishment.getId(), List.of(ProfessionalStatus.WORKING, ProfessionalStatus.ON_BREAK));
        for (Professional professional : inShift) {
            professional.setWorkStatus(ProfessionalStatus.OFF);
            professional.setAcceptingNew(false);
        }
        if (!inShift.isEmpty()) {
            professionalRepository.saveAll(inShift);
            log.info("[OpenStatus] loja {} fechada — {} profissional(is) foram para OFF",
                    establishment.getId(), inShift.size());
        }
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
