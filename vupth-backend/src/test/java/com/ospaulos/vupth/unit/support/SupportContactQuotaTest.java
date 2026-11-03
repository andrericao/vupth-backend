package com.ospaulos.vupth.unit.support;

import com.ospaulos.vupth.domain.support.exception.SupportDailyLimitException;
import com.ospaulos.vupth.domain.support.service.SupportContactQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Cota do dia do formulario do suporte (contato-do-suporte, DEC-169): teto por IP e teto geral. */
class SupportContactQuotaTest {

    @Test
    @DisplayName("o IP passa ate o teto do dia e o excedente e barrado; outro IP segue livre")
    void perIp() {
        SupportContactQuota quota = new SupportContactQuota(2, 100);
        quota.take("1.1.1.1");
        quota.take("1.1.1.1");
        assertThrows(SupportDailyLimitException.class, () -> quota.take("1.1.1.1"));
        assertDoesNotThrow(() -> quota.take("2.2.2.2"));
    }

    @Test
    @DisplayName("o teto geral barra qualquer IP (a cota separada do e-mail transacional)")
    void total() {
        SupportContactQuota quota = new SupportContactQuota(5, 2);
        quota.take("1.1.1.1");
        quota.take("2.2.2.2");
        assertThrows(SupportDailyLimitException.class, () -> quota.take("3.3.3.3"));
    }
}
