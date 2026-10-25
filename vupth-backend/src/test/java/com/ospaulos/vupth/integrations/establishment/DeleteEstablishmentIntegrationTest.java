package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — o dono apaga a loja ({@code DELETE /establishment/{id}}, desativar-conta).
 *
 * <p>Senha (401), fila viva (409), so o dono (403), e o caminho feliz: a loja vira REMOVED com o nome
 * "Loja removida", sai da vitrine, os profissionais saem dela, e o historico do cliente traz
 * {@code establishmentStatus: REMOVED}.</p>
 */
@Slf4j
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeleteEstablishmentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired UserRepository userRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired ProfessionalRepository professionalRepository;

    private final Faker faker = new Faker();

    private record Store(TestUserUtils.UserAuthAndToken owner, String id, String itemId) {}

    private Store store() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id(), "Prof");
        var item = TestUserUtils.createServiceItem(mvc, mapper, estab.id(), owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        return new Store(owner, estab.id(), item.id());
    }

    private ResultActions deleteStore(String id, String token, String password) throws Exception {
        return mvc.perform(delete("/establishment/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token)
                .content("{\"current_password\":\"" + password + "\"}"));
    }

    private String createIntention(String clientToken, Store s) throws Exception {
        String tomorrow = com.ospaulos.vupth.domain.shared.BusinessClock.at(
                com.ospaulos.vupth.domain.shared.BusinessClock.today().plusDays(1), java.time.LocalTime.of(10, 0)).toString();
        String body = mvc.perform(post("/attendances/intentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content("{\"professionalId\":\"" + s.owner().id() + "\",\"serviceItemIds\":[\"" + s.itemId()
                                + "\"],\"desiredTime\":\"" + tomorrow + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new JSONObject(body).getString("id");
    }

    @Test
    @DisplayName("senha errada -> 401; outro usuario -> 403; a loja segue ACTIVE")
    void wrongPasswordOrNotOwner() throws Exception {
        Store s = store();
        deleteStore(s.id(), s.owner().token(), "errada")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));

        var other = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        deleteStore(s.id(), other.token(), other.password()).andExpect(status().isForbidden());

        assertThat(establishmentRepository.findById(UUID.fromString(s.id())).orElseThrow().getStatus())
                .isEqualTo(EstablishmentStatus.ACTIVE);
    }

    @Test
    @DisplayName("com agendamento vivo -> 409 ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES")
    void liveIntention_409() throws Exception {
        Store s = store();
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        createIntention(client.token(), s);

        deleteStore(s.id(), s.owner().token(), s.owner().password())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES"));
    }

    @Test
    @DisplayName("caminho feliz: REMOVED, 'Loja removida', fora da vitrine, sem profissionais; o historico diz REMOVED")
    void happyPath_removed() throws Exception {
        Store s = store();
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String intentionId = createIntention(client.token(), s);
        mvc.perform(delete("/attendances/intentions/{id}", intentionId).header("Authorization", client.token()))
                .andExpect(status().is2xxSuccessful());

        deleteStore(s.id(), s.owner().token(), s.owner().password()).andExpect(status().isNoContent());

        var removed = establishmentRepository.findById(UUID.fromString(s.id())).orElseThrow();
        assertThat(removed.getStatus()).isEqualTo(EstablishmentStatus.REMOVED);
        assertThat(removed.getName()).isEqualTo("Loja removida");
        assertThat(professionalRepository.findByEstablishmentId(UUID.fromString(s.id()))).isEmpty();

        String vitrine = mvc.perform(get("/establishment")).andReturn().getResponse().getContentAsString();
        assertThat(vitrine).doesNotContain(s.id());

        mvc.perform(get("/attendances/client/me").header("Authorization", client.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].establishmentStatus").value("REMOVED"))
                .andExpect(jsonPath("$[0].establishmentName").value("Loja removida"));

        deleteStore(s.id(), s.owner().token(), s.owner().password()).andExpect(status().isNotFound());
    }

    @AfterAll
    void tearDown() {
        jdbc.update("DELETE FROM cut_request"); // pedidos de corte de outras classes apontam para attendance
        attendanceRepository.deleteAll();
        userRepository.deleteAll();
    }
}
