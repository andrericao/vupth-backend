package com.ospaulos.vupth.domain.support.service;

import com.ospaulos.vupth.domain.support.exception.SupportDailyLimitException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cota do dia do formulario do suporte (contato-do-suporte, DEC-169): publico e sem login, entao cada IP
 * tem um teto por dia (UTC) e o formulario inteiro tem outro. O teto geral e a cota SEPARADA do e-mail
 * transacional: um robo com muitos IPs nao esgota o plano de envio do reset de senha e dos avisos.
 * Em memoria, zerada na virada do dia (custo zero, como o {@code AuthRateLimitFilter}).
 */
@Component
public class SupportContactQuota {

    private final int perIpPerDay;
    private final int totalPerDay;
    private final Clock clock;
    private final Map<String, Integer> perIp = new ConcurrentHashMap<>();
    private LocalDate day;
    private int total;

    @Autowired
    public SupportContactQuota(@Value("${vupth.support.daily-limit-per-ip:5}") int perIpPerDay,
                               @Value("${vupth.support.daily-limit-total:100}") int totalPerDay) {
        this(perIpPerDay, totalPerDay, Clock.systemUTC());
    }

    SupportContactQuota(int perIpPerDay, int totalPerDay, Clock clock) {
        this.perIpPerDay = perIpPerDay;
        this.totalPerDay = totalPerDay;
        this.clock = clock;
    }

    /** Conta uma mensagem do IP; passou do teto, lanca {@link SupportDailyLimitException}. */
    public synchronized void take(String ip) {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            total = 0;
            perIp.clear();
        }
        if (total >= totalPerDay || perIp.getOrDefault(ip, 0) >= perIpPerDay) {
            throw new SupportDailyLimitException();
        }
        total++;
        perIp.merge(ip, 1, Integer::sum);
    }
}
