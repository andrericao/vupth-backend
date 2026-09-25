package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Ambiente sem as variaveis R2_*: o upload responde 503 STORAGE_NOT_CONFIGURED em vez de subir
 * quebrado — e o que o staging/prod mostram ate alguem seguir o passo a passo do vupth-docs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfilePhotoUnconfiguredIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("Sem R2 configurado -> 503 STORAGE_NOT_CONFIGURED")
    void noStorage_503() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, new Faker());
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};

        mvc.perform(multipart("/profile/me/photo")
                        .file(new MockMultipartFile("file", "f.png", "image/png", png))
                        .with(r -> { r.setMethod("PUT"); return r; })
                        .header("Authorization", user.token()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_NOT_CONFIGURED"));
    }

    @AfterAll
    void tearDown() {
        userRepository.deleteAll();
    }
}
