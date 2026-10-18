package com.ospaulos.vupth.integrations.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** painel, fatia 3: o convite com permissoes, a gestao dos usuarios do painel e a protecao do dono. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminInvitationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @MockBean EmailSender emailSender;

    private final Faker faker = new Faker();

    private TestUserUtils.UserAuthAndToken owner() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        return user;
    }

    private String invite(String ownerToken, String email, String... permissions) throws Exception {
        String perms = String.join("\",\"", permissions);
        mvc.perform(post("/admin/invitations").header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"permissions\":[\"" + perms + "\"]}"))
                .andExpect(status().isCreated());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(eq(email.toLowerCase()), any(), body.capture());
        String text = body.getValue();
        return text.substring(text.indexOf("token=") + 6).trim();
    }

    private String accept(String token, String password, int expected) throws Exception {
        return mvc.perform(post("/admin/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private String login(String email, String password) throws Exception {
        return "Bearer " + mapper.readTree(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .at("/tokens/access_token").asText();
    }

    @Test
    @DisplayName("convite: o e-mail leva o link; o aceite cria a conta com a senha; so as permissoes escolhidas abrem; o token vale uma vez")
    void inviteAcceptAndPermissions() throws Exception {
        var owner = owner();
        String email = faker.internet().emailAddress();
        String token = invite(owner.token(), email, "USERS");

        mvc.perform(get("/admin/invitations").header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email=='" + email.toLowerCase() + "')].permissions[0]").value("USERS"));

        accept("token-que-nao-existe", "SenhaBoa123", 410);
        accept(token, "123", 422);
        assertThat(mapper.readTree(accept(token, "SenhaBoa123", 200)).path("next").asText()).isEqualTo("TOTP_SETUP");
        accept(token, "SenhaBoa123", 410);

        String invited = login(email, "SenhaBoa123");
        mvc.perform(get("/admin/users").header("Authorization", invited)).andExpect(status().isOk());
        mvc.perform(get("/admin/establishments").header("Authorization", invited)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/invitations").header("Authorization", invited).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@y.com\",\"permissions\":[\"USERS\"]}"))
                .andExpect(status().isForbidden());

        // convidar quem ja usa o painel: 409; sem permissao escolhida: 422
        mvc.perform(post("/admin/invitations").header("Authorization", owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"permissions\":[\"USERS\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_ALREADY_MEMBER"));
        mvc.perform(post("/admin/invitations").header("Authorization", owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"novo@vupth.test\",\"permissions\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSIONS_REQUIRED"));
    }

    @Test
    @DisplayName("quem ja tem conta ganha o papel e mantem a senha; convite revogado nao aceita")
    void existingAccountAndRevoke() throws Exception {
        var owner = owner();
        var existing = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String token = invite(owner.token(), existing.email(), "FINANCE");
        accept(token, "outra-senha-qualquer", 200);

        String again = login(existing.email(), existing.password());
        mvc.perform(get("/admin/referrals/payouts").header("Authorization", again)).andExpect(status().isOk());
        mvc.perform(get("/admin/users").header("Authorization", again)).andExpect(status().isForbidden());

        String other = faker.internet().emailAddress();
        String revoked = invite(owner.token(), other, "ALERTS");
        JsonNode pending = mapper.readTree(mvc.perform(get("/admin/invitations").header("Authorization", owner.token()))
                .andReturn().getResponse().getContentAsString());
        String id = null;
        for (JsonNode n : pending) {
            if (n.get("email").asText().equals(other.toLowerCase())) {
                id = n.get("id").asText();
            }
        }
        assertThat(id).isNotNull();
        mvc.perform(delete("/admin/invitations/{id}", id).header("Authorization", owner.token()))
                .andExpect(status().isNoContent());
        accept(revoked, "SenhaBoa123", 410);
    }

    @Test
    @DisplayName("gestao: lista com o dono, muda as permissoes, remove o acesso; dono e a si mesmo ficam protegidos")
    void manage() throws Exception {
        var owner = owner();
        String email = faker.internet().emailAddress();
        accept(invite(owner.token(), email, "ADMIN_USERS", "USERS"), "SenhaBoa123", 200);
        String manager = login(email, "SenhaBoa123");

        String listed = mvc.perform(get("/admin/users-admin").header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + owner.id() + "')].owner").value(true))
                .andExpect(jsonPath("$[?(@.email=='" + email.toLowerCase() + "')].owner").value(false))
                .andExpect(jsonPath("$[?(@.email=='" + email.toLowerCase() + "')].totp_enabled").value(false))
                .andReturn().getResponse().getContentAsString();
        String managerId = null;
        for (JsonNode n : mapper.readTree(listed)) {
            if (n.get("email").asText().equals(email.toLowerCase())) {
                managerId = n.get("id").asText();
            }
        }

        // o convidado com ADMIN_USERS nao mexe no dono nem em si
        mvc.perform(patch("/admin/users-admin/{id}", owner.id()).header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"permissions\":[\"USERS\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_OWNER_PROTECTED"));
        mvc.perform(delete("/admin/users-admin/{id}", managerId).header("Authorization", manager))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_SELF_CHANGE"));
        mvc.perform(delete("/admin/users-admin/{id}", UUID.randomUUID()).header("Authorization", owner.token()))
                .andExpect(status().isNotFound());

        // o dono muda as permissoes (pelo menos uma) e depois remove o acesso
        mvc.perform(patch("/admin/users-admin/{id}", managerId).header("Authorization", owner.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"permissions\":[]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/admin/users-admin/{id}", managerId).header("Authorization", owner.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"permissions\":[\"ALERTS\"]}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/users").header("Authorization", manager)).andExpect(status().isForbidden());
        mvc.perform(delete("/admin/users-admin/{id}", managerId).header("Authorization", owner.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/alerts").header("Authorization", manager)).andExpect(status().isForbidden());
    }
}
