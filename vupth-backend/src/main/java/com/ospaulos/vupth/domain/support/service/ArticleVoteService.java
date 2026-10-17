package com.ospaulos.vupth.domain.support.service;

import com.ospaulos.vupth.api.support.dto.response.ArticleVoteSummaryResponse;
import com.ospaulos.vupth.domain.support.exception.InvalidArticleSlugException;
import com.ospaulos.vupth.domain.support.model.ArticleVote;
import com.ospaulos.vupth.domain.support.model.ArticleVoteType;
import com.ospaulos.vupth.domain.support.repository.ArticleVoteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * O voto 👍/👎 dos artigos do site de suporte (voto-do-artigo). Um voto por IP, artigo e dia; o ultimo vale
 * (trocar o joinha pelo dedao nao soma dois). O IP nunca e guardado: so o SHA-256 dele com um sal.
 * O slug e livre, conferido so pelo formato — o Backend nao conhece a lista de artigos.
 */
@Service
public class ArticleVoteService {

    static final Pattern SLUG = Pattern.compile("[a-z0-9-]{1,80}");

    private final ArticleVoteRepository repository;
    private final String salt;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ArticleVoteService(ArticleVoteRepository repository,
                              @Value("${vupth.support.vote.ip-salt:vupth-article-vote}") String salt) {
        this(repository, salt, Clock.systemUTC());
    }

    ArticleVoteService(ArticleVoteRepository repository, String salt, Clock clock) {
        this.repository = repository;
        this.salt = salt;
        this.clock = clock;
    }

    @Transactional
    public void vote(String slug, ArticleVoteType type, String ip) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new InvalidArticleSlugException();
        }
        LocalDate today = LocalDate.now(clock);
        String ipHash = hash(ip);
        ArticleVote vote = repository.findBySlugAndIpHashAndVotedOn(slug, ipHash, today).orElseGet(() -> {
            ArticleVote created = new ArticleVote();
            created.setId(UUID.randomUUID());
            created.setSlug(slug);
            created.setIpHash(ipHash);
            created.setVotedOn(today);
            return created;
        });
        vote.setVote(type);
        vote.setUpdatedAt(OffsetDateTime.now(clock));
        repository.save(vote);
    }

    @Transactional(readOnly = true)
    public List<ArticleVoteSummaryResponse> summary() {
        return repository.summary();
    }

    private String hash(String ip) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((salt + "|" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
