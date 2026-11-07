package com.ospaulos.vupth.domain.feature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * recursos-infraestrutura (Andre, 06/11): uma chave do app. {@code profiles}, {@code regions} e {@code accounts} sao listas gravadas
 * separadas por virgula; lista vazia de perfil ou regiao nao recorta. Quem esta em {@code accounts} nao passa pela porcentagem.
 */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "app_feature")
public class AppFeature {

    @Id
    @Column(name = "feature_key", length = 60)
    private String key;

    @Column(name = "description", length = 200, nullable = false)
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "percent", nullable = false)
    private int percent;

    @Column(name = "profiles", length = 60, nullable = false)
    private String profiles = "";

    @Column(name = "regions", nullable = false)
    private String regions = "";

    @Column(name = "accounts", nullable = false)
    private String accounts = "";

    @Column(name = "depends_on", length = 60)
    private String dependsOn;

    /** O artigo do recurso no Suporte; nulo = chave interna, sem artigo. */
    @Column(name = "article_path", length = 200)
    private String articlePath;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    public List<String> profileList() {
        return split(profiles);
    }

    public List<String> regionList() {
        return split(regions);
    }

    public List<String> accountList() {
        return split(accounts);
    }

    public void apply(boolean enabled, int percent, List<String> profiles, List<String> regions, List<String> accounts,
                      UUID updatedBy, OffsetDateTime now) {
        this.enabled = enabled;
        this.percent = percent;
        this.profiles = String.join(",", profiles);
        this.regions = String.join(",", regions);
        this.accounts = String.join(",", accounts);
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.asList(value.split(","));
    }
}
