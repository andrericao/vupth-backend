package com.ospaulos.vupth.domain.feature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma mudanca de chave: quem, quando, por que, o valor anterior e o novo (JSON). So recebe linhas novas. */
@Getter
@Entity
@Immutable
@NoArgsConstructor
@Table(name = "app_feature_history")
public class AppFeatureHistory {

    @Id
    private UUID id;

    @Column(name = "feature_key", length = 60, nullable = false)
    private String featureKey;

    @Column(name = "changed_by", nullable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    @Column(name = "reason", length = 300, nullable = false)
    private String reason;

    @Column(name = "previous_value", nullable = false)
    private String previousValue;

    @Column(name = "new_value", nullable = false)
    private String newValue;

    public AppFeatureHistory(String featureKey, UUID changedBy, OffsetDateTime changedAt, String reason, String previousValue, String newValue) {
        this.id = UUID.randomUUID();
        this.featureKey = featureKey;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
        this.reason = reason;
        this.previousValue = previousValue;
        this.newValue = newValue;
    }
}
