package com.ospaulos.vupth.domain.support.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** O voto de um IP (so o hash) num artigo do site de suporte, num dia (voto-do-artigo). O ultimo voto do dia vale. */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "article_vote")
public class ArticleVote {

    @Id
    private UUID id;

    @Column(name = "slug", length = 80, nullable = false)
    private String slug;

    @Column(name = "ip_hash", length = 64, nullable = false)
    private String ipHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "vote", length = 4, nullable = false)
    private ArticleVoteType vote;

    @Column(name = "voted_on", nullable = false)
    private LocalDate votedOn;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
