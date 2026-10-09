package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — desativar a conta (desativar-conta, 08/10).
 *
 * <p>{@code POST /profile/deactivate/me} (senha, portoes do apagar), o login com {@code deactivated: true},
 * o "so consultar" (le, e a escrita da 403 ACCOUNT_DEACTIVATED), {@code POST /profile/reactivate/me} e a
 * loja do dono que sai da vitrine e recusa pedido de corte (409 ESTABLISHMENT_UNAVAILABLE).</p>
 */
@Slf4j
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeactivateAccountIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;

    private final Faker faker = new Faker();

    private ResultActions deactivate(String token, String password) throws Exception {
        return mvc.perform(post("/profile/deactivate/me")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token)
                .content("{\"current_password\":\"" + password + "\"}"));
    }

    private JSONObject login(String email, String password) throws Exception {
        String body = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new JSONObject(body);
    }

    private static String bearer(JSONObject login) throws Exception {
        return "Bearer " + login.getJSONObject("tokens").getString("access_token");
    }

    @Test
    @DisplayName("senha errada -> 401 CURRENT_PASSWORD_INVALID e a conta segue em uso")
    void wrongPassword_401() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        deactivate(user.token(), "errada")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));
        assertThat(login(user.email(), user.password()).getBoolean("deactivated")).isFalse();
    }

    @Test
    @DisplayName("ciclo: desativa -> login deactivated=true -> le (200), escreve (403) -> reativa -> login false")
    void deactivate_readOnly_reactivate() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        deactivate(user.token(), user.password()).andExpect(status().isNoContent());

        JSONObject first = login(user.email(), user.password());
        assertThat(first.getBoolean("deactivated")).isTrue();
        String token = bearer(first);

        mvc.perform(get("/profile/me").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"first_name\":\"Outro\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DEACTIVATED"));

        mvc.perform(post("/profile/reactivate/me").header("Authorization", token))
                .andExpect(status().isNoContent());
        assertThat(login(user.email(), user.password()).getBoolean("deactivated")).isFalse();
    }

    @Test
    @DisplayName("dono desativa: a loja vira DEACTIVATED, sai da vitrine e recusa pedido de corte; reativa e volta")
    void owner_storeDeactivated_thenBack() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id(), "Prof Dono");
        var item = TestUserUtils.createServiceItem(mvc, mapper, estab.id(), owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));

        deactivate(owner.token(), owner.password()).andExpect(status().isNoContent());
        assertThat(establishmentRepository.findById(UUID.fromString(estab.id())).orElseThrow().getStatus())
                .isEqualTo(EstablishmentStatus.DEACTIVATED);

        String ownerToken = bearer(login(owner.email(), owner.password()));
        mvc.perform(get("/professional/me").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.establishment_status").value("DEACTIVATED"));

        String vitrine = mvc.perform(get("/establishment")).andReturn().getResponse().getContentAsString();
        assertThat(vitrine).doesNotContain(estab.id());

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/attendances/cut-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content("{\"professionalId\":\"" + owner.id() + "\",\"establishmentId\":\"" + estab.id()
                                + "\",\"serviceItemIds\":[\"" + item.id() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_UNAVAILABLE"));

        mvc.perform(post("/profile/reactivate/me").header("Authorization", ownerToken))
                .andExpect(status().isNoContent());
        assertThat(establishmentRepository.findById(UUID.fromString(estab.id())).orElseThrow().getStatus())
                .isEqualTo(EstablishmentStatus.ACTIVE);
    }

    @Test
    @DisplayName("cliente numa fila (intencao viva) -> 409 ACCOUNT_DEACTIVATE_NOT_ALLOWED")
    void clientWithLiveIntention_409() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id(), "Prof");
        var item = TestUserUtils.createServiceItem(mvc, mapper, estab.id(), owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String tomorrow = com.ospaulos.vupth.domain.shared.BusinessClock.at(
                com.ospaulos.vupth.domain.shared.BusinessClock.today().plusDays(1), java.time.LocalTime.of(10, 0)).toString();
        mvc.perform(post("/attendances/intentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content("{\"professionalId\":\"" + owner.id() + "\",\"serviceItemIds\":[\"" + item.id()
                                + "\"],\"desiredTime\":\"" + tomorrow + "\"}"))
                .andExpect(status().isCreated());

        deactivate(client.token(), client.password())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DEACTIVATE_NOT_ALLOWED"));
    }
}
