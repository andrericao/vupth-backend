package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.transfer.OwnershipTransferRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — passar a posse da loja com aceite (desativar-conta, 08/10).
 *
 * <p>So para profissional ativo da loja (422), um pendente por loja (409), o convite na lista do
 * convidado, aceitar (o dono muda), recusar, cancelar (404 depois) e o PATCH antigo (410).</p>
 */
@Slf4j
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnershipTransferIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired OwnershipTransferRepository ownershipTransferRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    private record Team(TestUserUtils.UserAuthAndToken owner, TestUserUtils.UserAuthAndToken employee, String storeId) {}

    private Team team() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id(), "Dono");
        var employee = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, employee.token(), estab.id(), "Funcionario");
        return new Team(owner, employee, estab.id());
    }

    private ResultActions requestTo(Team t, String professionalId, String token) throws Exception {
        return mvc.perform(post("/establishment/{id}/ownership-transfer", t.storeId())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token)
                .content("{\"professionalId\":\"" + professionalId + "\"}"));
    }

    private String createdId(ResultActions r) throws Exception {
        return new JSONObject(r.andReturn().getResponse().getContentAsString()).getString("id");
    }

    @Test
    @DisplayName("fora da equipe -> 422; pedido -> 201; de novo -> 409; convite na lista; aceite muda o dono")
    void request_accept_changesOwner() throws Exception {
        Team t = team();
        var outsider = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        requestTo(t, outsider.id(), t.owner().token())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_TARGET_NOT_IN_TEAM"));

        String id = createdId(requestTo(t, t.employee().id(), t.owner().token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.professional_id").value(t.employee().id())));

        requestTo(t, t.employee().id(), t.owner().token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_ALREADY_PENDING"));

        mvc.perform(get("/establishment/{id}/ownership-transfer", t.storeId()).header("Authorization", t.owner().token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        mvc.perform(get("/professional/me/ownership-transfers").header("Authorization", t.employee().token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].establishment_id").value(t.storeId()))
                .andExpect(jsonPath("$[0].from_owner_name").exists());

        mvc.perform(post("/ownership-transfers/{id}/accept", id).header("Authorization", outsider.token()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/ownership-transfers/{id}/accept", id).header("Authorization", t.employee().token()))
                .andExpect(status().isNoContent());

        assertThat(establishmentRepository.findByIdAndOwnerId(UUID.fromString(t.storeId()), UUID.fromString(t.employee().id())))
                .as("o funcionario virou o dono")
                .isPresent();
        mvc.perform(get("/establishment/{id}/ownership-transfer", t.storeId()).header("Authorization", t.owner().token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("cancelar -> 204 e o pendente some (404); recusar -> 204 e o dono continua")
    void cancel_and_refuse() throws Exception {
        Team t = team();
        String first = createdId(requestTo(t, t.employee().id(), t.owner().token()).andExpect(status().isCreated()));

        mvc.perform(delete("/ownership-transfers/{id}", first).header("Authorization", t.owner().token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/establishment/{id}/ownership-transfer", t.storeId()).header("Authorization", t.owner().token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_TRANSFER_NOT_FOUND"));

        String second = createdId(requestTo(t, t.employee().id(), t.owner().token()).andExpect(status().isCreated()));
        mvc.perform(post("/ownership-transfers/{id}/refuse", second).header("Authorization", t.employee().token()))
                .andExpect(status().isNoContent());

        assertThat(establishmentRepository.findByIdAndOwnerId(UUID.fromString(t.storeId()), UUID.fromString(t.owner().id())))
                .isPresent();
    }

    @Test
    @DisplayName("o PATCH /establishment/{id}/owner antigo -> 410")
    void oldPatch_gone() throws Exception {
        Team t = team();
        mvc.perform(patch("/establishment/{id}/owner", t.storeId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", t.owner().token())
                        .content("{\"newOwnerId\":\"" + t.employee().id() + "\"}"))
                .andExpect(status().isGone());
    }

    @AfterAll
    void tearDown() {
        ownershipTransferRepository.deleteAll();
        userRepository.deleteAll();
    }
}
