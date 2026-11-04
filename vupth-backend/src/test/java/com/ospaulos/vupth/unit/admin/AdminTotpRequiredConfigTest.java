package com.ospaulos.vupth.unit.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** FRAQUEZAS F2 (03/11): o segundo fator do painel e obrigatorio para todo ADMIN na producao, por padrao (o staging fica opcional ate o Andre cadastrar o dele). */
@DisplayName("F2: 2o fator do admin ligado por padrao na producao")
class AdminTotpRequiredConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {"application-prod.properties"})
    void totpOnByDefault(String file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/config/" + file)) {
            assertThat(in).as(file).isNotNull();
            p.load(in);
        }
        assertThat(p.getProperty("vupth.admin.totp.required")).as(file).isEqualTo("${ADMIN_TOTP_REQUIRED:true}");
    }
}
