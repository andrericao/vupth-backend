package com.ospaulos.vupth.domain.attendance.fitin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * O pedido de encaixe na fila do dia (encaixe-diluido, DEC-136 a 141): o cliente pede para entrar depois de {@code aheadCount}
 * clientes e propoe um adicional; o profissional aceita, recusa ou contrapoe, e o cliente aceita ou contrapoe de volta. O adicional
 * nao tem relacao com o preco do corte; metade e do profissional e metade do app. So o cliente e o profissional veem o pedido.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "fit_in_request")
public class FitInRequest {

    public static final String CLIENT = "CLIENT";
    public static final String PROFESSIONAL = "PROFESSIONAL";

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "professional_id", nullable = false)
    private UUID professionalId;

    @Column(name = "establishment_id", nullable = false)
    private UUID establishmentId;

    /** Os servicos do pedido, ids separados por virgula. */
    @Column(name = "service_item_ids", nullable = false, columnDefinition = "TEXT")
    private String serviceItemIds;

    /** Depois de quantos clientes o encaixe entra. */
    @Column(name = "ahead_count", nullable = false)
    private int aheadCount;

    /** O horario em que o cliente quis chegar: passou, o pedido vence. */
    @Column(name = "desired_time", nullable = false)
    private OffsetDateTime desiredTime;

    /** O adicional da ultima proposta, de quem estiver em {@code lastOfferBy}. */
    @Column(name = "current_extra", nullable = false, precision = 10, scale = 2)
    private BigDecimal currentExtra;

    @Column(name = "last_offer_by", nullable = false, length = 12)
    private String lastOfferBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private FitInStatus status;

    /** O adicional combinado, gravado ao aceitar. */
    @Column(name = "final_extra", precision = 10, scale = 2)
    private BigDecimal finalExtra;

    @Column(name = "resulting_attendance_id")
    private UUID resultingAttendanceId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    public List<UUID> serviceItems() {
        return Arrays.stream(serviceItemIds.split(",")).filter(s -> !s.isBlank()).map(UUID::fromString).toList();
    }

    public boolean involves(UUID userId) {
        return userId != null && (userId.equals(clientId) || userId.equals(professionalId));
    }
}
