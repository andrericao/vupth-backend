package com.ospaulos.vupth.infrastructure.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Cache em memoria (Caffeine) para leituras que mudam pouco. Nada da fila em tempo real entra aqui.
 *
 * <p>O gerenciador e transacional: um evict feito dentro de uma transacao so vale depois do commit —
 * sem isso, um leitor concorrente recolocaria no cache o estado de antes da escrita. O TTL e a rede de
 * seguranca para mais de uma instancia (cada uma tem o seu cache e nao ve o evict da outra).</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SERVICE_TYPE_KEYWORDS = "service-type-keywords";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager caffeine = new CaffeineCacheManager(SERVICE_TYPE_KEYWORDS);
        caffeine.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(100));
        return new TransactionAwareCacheManagerProxy(caffeine);
    }
}
