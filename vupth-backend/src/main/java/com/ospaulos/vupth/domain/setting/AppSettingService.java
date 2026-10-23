package com.ospaulos.vupth.domain.setting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Leitura e escrita das configuracoes do Painel. Sem a chave gravada vale o padrao de quem pergunta. */
@Service
@RequiredArgsConstructor
@Transactional
public class AppSettingService {

    /** A divida do "pagamento por fora" e real (so depois de o Asaas estar implantado); desligada, tudo e teste. */
    public static final String DEBT_REAL = "debt.real";

    /** valor-minimo-do-servico (DEC-121): o menor preco de servico sem aviso, em reais; muda sem mexer no codigo. */
    public static final String SERVICE_MIN_PRICE = "service.min_price";
    public static final BigDecimal SERVICE_MIN_PRICE_DEFAULT = new BigDecimal("5.00");

    private final AppSettingRepository repository;

    @Transactional(readOnly = true)
    public boolean flag(String key, boolean fallback) {
        return repository.findById(key).map(s -> Boolean.parseBoolean(s.getValue())).orElse(fallback);
    }

    public void setFlag(String key, boolean value, UUID by) {
        repository.save(new AppSetting(key, Boolean.toString(value), OffsetDateTime.now(), by));
    }

    @Transactional(readOnly = true)
    public BigDecimal decimal(String key, BigDecimal fallback) {
        return repository.findById(key).map(s -> new BigDecimal(s.getValue())).orElse(fallback);
    }

    public void setDecimal(String key, BigDecimal value, UUID by) {
        repository.save(new AppSetting(key, value.toPlainString(), OffsetDateTime.now(), by));
    }

    @Transactional(readOnly = true)
    public BigDecimal serviceMinPrice() {
        return decimal(SERVICE_MIN_PRICE, SERVICE_MIN_PRICE_DEFAULT);
    }
}
