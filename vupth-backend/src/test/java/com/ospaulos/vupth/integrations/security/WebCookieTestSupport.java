package com.ospaulos.vupth.integrations.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** token-web-httponly (DEC-179): o token da web em cookie HttpOnly com CSRF por token duplo, atras da chave; o nativo nao muda. */
final class WebCookieTestSupport {

    private WebCookieTestSupport() {}

    public static Map<String, String> cookies(MvcResult r) {
        Map<String, String> out = new HashMap<>();
        for (String h : r.getResponse().getHeaders("Set-Cookie")) {
            String pair = h.split(";", 2)[0];
            int eq = pair.indexOf('=');
            out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    public static String device() {
        return "{\"token\":\"ExponentPushToken[" + UUID.randomUUID() + "]\",\"platform\":\"web\"}";
    }
}
