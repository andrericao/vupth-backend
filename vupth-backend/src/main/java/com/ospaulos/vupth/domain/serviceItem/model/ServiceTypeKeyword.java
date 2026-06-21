package com.ospaulos.vupth.domain.serviceItem.model;

import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sinonimo/palavra-chave adicionado por um profissional a um tipo de servico
 * (ServiceItemType). Global por tipo (vale para a busca de todos) e complementa os
 * sinonimos-base do enum. {@code keyword} e sempre armazenado NORMALIZADO (minuscula,
 * sem acento) — ver ServiceTypeKeywordServiceImpl. Unicidade por (tipo, keyword).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "service_type_keyword",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_service_type_keyword",
               columnNames = {"service_item_type", "keyword"}))
public class ServiceTypeKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_item_type", nullable = false, length = 60)
    private ServiceItemType serviceItemType;

    @Column(name = "keyword", nullable = false, length = 60)
    private String keyword;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ServiceTypeKeyword(ServiceItemType serviceItemType, String keyword, UUID createdByUserId) {
        this.serviceItemType = serviceItemType;
        this.keyword = keyword;
        this.createdByUserId = createdByUserId;
    }
}
