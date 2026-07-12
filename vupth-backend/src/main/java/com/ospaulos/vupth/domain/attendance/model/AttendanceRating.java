package com.ospaulos.vupth.domain.attendance.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Avaliacao (rating) de um atendimento concluido (item #6).
 *
 * <p>O cliente dono do atendimento avalia com 1-5 estrelas (inteiro) + comentario opcional, uma
 * unica vez ({@code attendance_id} UNIQUE — 2o voto e 409). A nota alimenta agregados
 * denormalizados ({@code ratingSum}/{@code ratingCount}) no
 * {@link com.ospaulos.vupth.domain.professional.model.Professional} e, quando o atendimento tem
 * <b>exatamente 1 servico</b>, tambem no
 * {@link com.ospaulos.vupth.domain.serviceItem.model.ServiceItem} correspondente — nesse caso
 * {@code serviceItemId} guarda a origem do voto (para auditoria/recalculo). Com 2+ servicos
 * (combo) nao da para saber qual servico levou qual nota, entao {@code serviceItemId} fica null e
 * a nota vai somente ao profissional.</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "attendance_rating", uniqueConstraints = {
        @UniqueConstraint(name = "uq_attendance_rating_attendance", columnNames = "attendance_id")
})
public class AttendanceRating implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Atendimento avaliado (1 voto por atendimento). */
    @Column(name = "attendance_id", nullable = false, unique = true)
    private UUID attendanceId;

    /** Nota inteira de 1 a 5. */
    @Column(name = "score", nullable = false)
    private int score;

    /** Comentario livre opcional (ate 500 chars). */
    @Column(name = "comment", length = 500)
    private String comment;

    /**
     * ServiceItem que originou o voto — preenchido <b>so</b> quando o atendimento tinha exatamente
     * 1 servico; null caso contrario (combo). Guarda a origem para auditoria/recalculo dos agregados.
     */
    @Column(name = "service_item_id")
    private UUID serviceItemId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public AttendanceRating(UUID attendanceId, int score, String comment, UUID serviceItemId) {
        this.attendanceId = attendanceId;
        this.score = score;
        this.comment = comment;
        this.serviceItemId = serviceItemId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
