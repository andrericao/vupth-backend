package com.ospaulos.vupth.infrastructure.config;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * Injeta o fuso de negocio configurado em {@link BusinessClock} no boot (BUG2b).
 *
 * <p>{@code vupth.timezone} (default {@code America/Sao_Paulo}) e o fuso em que a loja pensa: e
 * contra ele que o backend compara "agora" com {@code opening_time}/{@code closing_time} e projeta
 * a refeicao recorrente do dia. Produto single-timezone — o dia em que houver loja em outro fuso,
 * isto vira coluna do estabelecimento e o {@code BusinessClock} passa a receber o fuso por
 * parametro.</p>
 */
@Slf4j
@Component
public class BusinessClockConfig {

    @Value("${vupth.timezone:America/Sao_Paulo}")
    private String timezone;

    @PostConstruct
    public void configure() {
        try {
            BusinessClock.configure(ZoneId.of(timezone));
        } catch (Exception e) {
            log.warn("[BusinessClock] vupth.timezone invalido ('{}') — mantendo o default {}",
                    timezone, BusinessClock.DEFAULT_ZONE);
            BusinessClock.configure(BusinessClock.DEFAULT_ZONE);
        }
        log.info("[BusinessClock] fuso de negocio = {}", BusinessClock.zone());
    }
}
