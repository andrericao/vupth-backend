package com.ospaulos.vupth.domain.setting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma configuracao do app que o Painel liga e desliga (chave e valor), com quem mudou e quando. */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(name = "setting_key", length = 60)
    private String key;

    @Column(name = "setting_value", length = 200, nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    public AppSetting(String key, String value, OffsetDateTime updatedAt, UUID updatedBy) {
        this.key = key;
        this.value = value;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }
}
