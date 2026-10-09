package com.ospaulos.vupth.domain.establishment.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.establishment.valueObject.WeekdayHours;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.user.model.UserAddress;
import org.hibernate.annotations.BatchSize;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import com.ospaulos.vupth.domain.shared.BusinessClock;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name="ESTABLISHMENT")
public class Establishment implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "establishment_services",
            joinColumns = @JoinColumn(name = "establishment_id"))
    @Column(name = "service", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<ServiceType> services;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String urlImage;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "instagram", length = 100)
    private String instagram;

    // Horario de funcionamento (opcional, editavel). Nullable: estabelecimentos
    // antigos / recem-criados podem nao ter ainda. Usado no gate de abertura e na
    // validacao de que as refeicoes cabem na janela de funcionamento (#11g2).
    //
    // BUG2b: LocalTime (relogio de parede no fuso da loja), NAO OffsetTime — a coluna e TIME
    // (sem timezone) e nao tem onde guardar offset; com OffsetTime o Hibernate normalizava para
    // UTC (08:00-03:00 gravava 11:00 e devolvia "11:00Z"). Comparacoes com "agora" usam
    // BusinessClock (fuso vupth.timezone).
    //
    // #2 (16/09): estas duas colunas viraram ESPELHO do horario unico antigo — ninguem mais le daqui.
    // O horario vale pelo BusinessHours (semana + excecoes); o app antigo escreve por
    // applySingleWindow, que aplica o mesmo horario aos 7 dias.
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Column(name = "opening_time")
    private LocalTime openingTime;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Column(name = "closing_time")
    private LocalTime closingTime;

    /**
     * Horario por dia (#2): a semana (7 linhas, ou nenhuma = nao configurada) e as excecoes por data.
     * So se le e escreve pelo {@link BusinessHours} ({@link #getBusinessHours} /
     * {@link #applyBusinessHours}), que guarda as invariantes. {@code @BatchSize} pelo mesmo motivo do
     * endereco (B10): a listagem le o horario de TODA loja.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "establishment_weekly_hours", joinColumns = @JoinColumn(name = "establishment_id"))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "day_of_week")
    @BatchSize(size = 100)
    private Map<DayOfWeek, DayHoursColumns> weeklyHours = new EnumMap<>(DayOfWeek.class);

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "establishment_date_hours", joinColumns = @JoinColumn(name = "establishment_id"))
    @MapKeyColumn(name = "hours_date")
    @BatchSize(size = 100)
    private Map<LocalDate, DayHoursColumns> dateHours = new HashMap<>();

    @CreationTimestamp
    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type")
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EstablishmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_open_status", nullable = false)
    private OpenStatus openStatus;

    @Column(name = "last_status_change")
    private ZonedDateTime lastStatusChange;

    @JsonManagedReference("establishment-professionals")
    @OneToMany(mappedBy = "establishment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Professional> professionals = new ArrayList<>();

    /**
     * <b>{@code @BatchSize} (B10):</b> o endereco e lido no mapeamento de TODA loja da listagem
     * publica ({@code getMainAddress}), e as consultas de lista nao dao {@code JOIN FETCH} nele.
     * Sem o lote, era mais uma ida ao banco <b>por loja</b> — o mesmo N+1 do resumo de fila, so que
     * escondido no DTO. Com ele, as colecoes de ate 100 lojas vem numa consulta so.
     */
    @JsonManagedReference
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "establishment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EstablishmentAddress> addresses = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonBackReference
    @JoinColumn(name = "owner_id",
        foreignKey = @ForeignKey(name = "fk_establishment_owner"))
    private Owner owner;

    public boolean addProfessional(Professional professional) {
            return professionals.add(professional);
    }

    public boolean removeEmployee(Professional professional) {
        return professionals.remove(professional);
    }

    public boolean hasProfessional(Professional professional) {
        return professionals.contains(professional);
    }

    public Set<Professional> getAllProfessionals() {
        return new HashSet<>(professionals);
    }

    public List<ServiceItem> getServicesItemByEstablishment(){
        return professionals.stream()
                .flatMap(p -> p.getServices().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    public Set<Professional> getAvailableProfessionals() {
        return professionals.stream()
                .filter(Professional::getIsAvailable)
                .collect(Collectors.toSet());
    }

    public Set<Professional> getProfessionalsBySpecialty(String specialty) {
        return professionals.stream()
                .filter(p -> p.getSpecialty().equalsIgnoreCase(specialty))
                .collect(Collectors.toSet());
    }

    public void addAddress(EstablishmentAddress address) {
        addresses.add(address);
        address.setEstablishment(this);
    }

    public void removeAddress(EstablishmentAddress address) {
        addresses.remove(address);
        address.setEstablishment(null);
    }

    /**
     * Endereco principal da loja para o read-back (Partner BUG4).
     * Prioriza o marcado {@code isMain}; senao o primeiro cadastrado; {@code null} se nao houver.
     */
    public Address getMainAddress() {
        return addresses.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsMain()))
                .findFirst()
                .or(() -> addresses.stream().findFirst())
                .map(EstablishmentAddress::getAddress)
                .orElse(null);
    }

    /**
     * "Aberto agora" efetivo (BUG5 parte B): a loja so esta realmente recebendo
     * quando o {@code openStatus} e {@code OPEN} <b>e</b> o horario atual cai dentro
     * da janela {@code [openingTime, closingTime)}.
     * <p>
     * Campo de LEITURA derivado — nao muda o {@code openStatus} persistido (nao
     * ha auto-fechamento; fechar e sempre manual). Serve pro front-client decidir
     * se mostra a loja como aberta e (no futuro) pro gate de entrada na fila.
     * <p>
     * Sem janela definida ({@code openingTime}/{@code closingTime} nulos) so o
     * {@code openStatus} manda — nao ha como recortar por horario.
     */
    public BusinessHours getBusinessHours() {
        List<WeekdayHours> week = weeklyHours.entrySet().stream()
                .map(e -> new WeekdayHours(e.getKey(), e.getValue().toDayHours()))
                .toList();
        Map<LocalDate, DayHours> exceptions = new HashMap<>();
        dateHours.forEach((date, columns) -> exceptions.put(date, columns.toDayHours()));
        return BusinessHours.of(week, exceptions);
    }

    /** Grava o horario por dia. As colecoes gerenciadas sao reaproveitadas (clear + put). */
    public void applyBusinessHours(BusinessHours hours) {
        weeklyHours.clear();
        hours.week().forEach(w -> weeklyHours.put(w.day(), DayHoursColumns.from(w.hours())));
        dateHours.clear();
        hours.allExceptions().forEach((date, h) -> dateHours.put(date, DayHoursColumns.from(h)));
    }

    /**
     * O horario unico do app antigo (PATCH general-infos): vale para os 7 dias e mantem as excecoes.
     * Um dos dois nulo = a loja volta a "nao configurada". {@code null} num argumento mantem o valor
     * espelhado anterior (o app antigo podia mandar so um dos dois).
     */
    public void applySingleWindow(LocalTime opening, LocalTime closing) {
        if (opening != null) this.openingTime = opening;
        if (closing != null) this.closingTime = closing;
        BusinessHours current = getBusinessHours();
        if (this.openingTime == null || this.closingTime == null) {
            applyBusinessHours(BusinessHours.of(List.of(), current.allExceptions()));
            return;
        }
        DayHours single = DayHours.open(this.openingTime, this.closingTime);
        applyBusinessHours(current.withWeek(Arrays.stream(DayOfWeek.values())
                .map(day -> new WeekdayHours(day, single))
                .toList()));
    }

    /** Horario de hoje (dia da loja), ou {@code null} se nao configurada. */
    public DayHours todayHours() {
        return getBusinessHours().on(BusinessClock.today());
    }

    /** Abertura de hoje — {@code null} se hoje fecha ou a loja nao configurou. */
    public LocalTime todayOpens() {
        DayHours today = todayHours();
        return today == null ? null : today.opens();
    }

    /** Fechamento de hoje — {@code null} se hoje fecha ou a loja nao configurou. */
    public LocalTime todayCloses() {
        DayHours today = todayHours();
        return today == null ? null : today.closes();
    }

    /**
     * "Aberto agora" efetivo. Desde 18/09 e <b>so</b> o {@code open_status} — que por sua vez deriva de
     * quem esta em expediente (D11: quem abre a loja e a chegada do primeiro profissional, nao o
     * relogio). O horario publicado e o <b>plano do dia</b>: ele guia o auto-fechamento e a agenda, e
     * nao fecha uma loja que esta atendendo. No aparelho, a regra antiga mostrava a loja FECHADA com o
     * profissional Trabalhando e a fila aberta.
     */
    public boolean isOpenNow() {
        return openStatus == OpenStatus.OPEN;
    }

    /**
     * A hora de parede {@code t} de HOJE cai dentro do horario de hoje? Diferente de
     * {@link #isOpenNow()}, <b>ignora o {@code openStatus}</b>. Para um instante de outro dia
     * (intencao, carencia), use {@link #isWithinBusinessHours(OffsetDateTime)}, que resolve o dia certo.
     * Nao configurada = qualquer hora vale.
     */
    public boolean isWithinBusinessHours(LocalTime t) {
        return getBusinessHours().isWithin(BusinessClock.today(), t);
    }

    /**
     * O instante {@code at} cai dentro do horario DO DIA dele (excecao, senao o dia da semana; inclui a
     * madrugada de uma virada aberta na vespera)? Nao configurada = sempre dentro.
     */
    public boolean isWithinBusinessHours(OffsetDateTime at) {
        return getBusinessHours().isWithin(BusinessClock.dateAt(at), BusinessClock.timeAt(at));
    }

    /** Recebe cliente (fila, pedido de corte, agendamento)? Desativada, removida ou inativa, nao. */
    public boolean acceptsClients() {
        return status != com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus.DEACTIVATED
                && status != com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus.REMOVED
                && status != com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus.INACTIVE;
    }
}
