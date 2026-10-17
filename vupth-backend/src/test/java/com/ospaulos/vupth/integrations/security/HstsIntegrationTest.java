package com.ospaulos.vupth.integrations.security;

import com.ospaulos.vupth.VupthApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S7 (DEC-81): HSTS so quando o pedido chegou por HTTPS (o proxy avisa no X-Forwarded-Proto). */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HstsIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("atras do proxy HTTPS: Strict-Transport-Security de 1 ano com subdominios")
    void behindHttpsProxy() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
    }

    @Test
    @DisplayName("em HTTP puro: sem o cabecalho")
    void plainHttp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
