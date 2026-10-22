package com.ospaulos.vupth.integrations.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** admin-caixa-de-suporte: o que o formulario recebe aparece em GET /admin/support/messages, so para ADMIN. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "vupth.support.inbox-email=suporte@vupth.test")
class AdminSupportMessageIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @MockBean EmailSender emailSender;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("o formulario grava; o admin lista com o assunto, da mais nova para a mais antiga; quem nao e admin leva 403")
    void inbox() throws Exception {
        String subject = "Caixa " + UUID.randomUUID();
        mvc.perform(post("/support/contact").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Ana\",\"email\":\"ana@exemplo.com\",\"perfil\":\"cliente\","
                                + "\"categoria\":\"DUVIDA\",\"assunto\":\"" + subject + "\",\"mensagem\":\"Oi\"}"))
                .andExpect(status().isNoContent());

        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/admin/support/messages").header("Authorization", user.token()))
                .andExpect(status().isForbidden());

        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);

        mvc.perform(get("/admin/support/messages").param("size", "1").header("Authorization", user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].subject").value(subject))
                .andExpect(jsonPath("$.items[0].email").value("ana@exemplo.com"))
                .andExpect(jsonPath("$.items[0].category").value("DUVIDA"))
                .andExpect(jsonPath("$.items[0].message").value("Oi"))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").isNumber());
    }
}
