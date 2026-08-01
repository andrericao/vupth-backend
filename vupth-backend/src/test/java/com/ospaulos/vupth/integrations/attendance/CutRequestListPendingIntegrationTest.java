package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — BUG5 C2 desbloqueio da UI do Partner: GET /attendances/cut-requests lista os
 * pedidos PENDING dirigidos ao profissional logado (caminho de recuperacao quando o WS foi perdido).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CutRequestListPendingIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CutRequestRepository cutRequestRepository;
    @Autowired AttendanceRepository attendanceRepository;

    private final Faker faker = new Faker();

    String clientToken, ownerProfToken, professionalId, establishmentId, serviceItemId;
    String anotherProfToken; // PROFESSIONAL de outra loja (sem pedidos)

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = owner.token();
        this.professionalId = owner.id();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();       // CLOSED por default -> permite solicitar corte
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Prof A");
        var s = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemId = s.id();

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        var another = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var anotherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, another.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, another.token(), anotherEstab.id(), "Prof B");
        this.anotherProfToken = another.token();

        log.info("[CutRequestListPending] setup — estab={} prof={}", establishmentId, professionalId);
    }

    @AfterEach
    void clean() {
        cutRequestRepository.deleteAll();     // FK resulting_attendance_id -> apaga o pedido antes
        attendanceRepository.deleteAll();
    }

    /** Cliente cria um pedido de corte (loja fechada) e devolve o id. */
    private String createRequest() throws Exception {
        String body = "{\"professionalId\":\"" + professionalId + "\",\"establishmentId\":\"" + establishmentId
                + "\",\"serviceItemIds\":[\"" + serviceItemId + "\"]}";
        MvcResult r = mvc.perform(post("/attendances/cut-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    @Test
    @Order(10)
    @DisplayName("GET pendentes -> so os PENDING do profissional, do mais antigo ao mais novo")
    void listPending_returnsOwnPending() throws Exception {
        String first = createRequest();
        String second = createRequest();

        mvc.perform(get("/attendances/cut-requests").header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first))   // ordenado por createdAt ASC
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].professional_id").value(professionalId))
                .andExpect(jsonPath("$[0].service_item_ids[0]").value(serviceItemId));
    }

    @Test
    @Order(20)
    @DisplayName("profissional de outra loja -> lista vazia (nao ve pedidos de outrem)")
    void listPending_otherProfessionalSeesNothing() throws Exception {
        createRequest();
        mvc.perform(get("/attendances/cut-requests").header("Authorization", anotherProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @Order(30)
    @DisplayName("apos aceitar, o pedido sai da lista de pendentes")
    void listPending_dropsAfterAccept() throws Exception {
        String id = createRequest();
        mvc.perform(patch("/attendances/cut-requests/{id}/accept", id)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        mvc.perform(get("/attendances/cut-requests").header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @Order(40)
    @DisplayName("cliente (role CLIENT) -> 403")
    void listPending_clientForbidden() throws Exception {
        mvc.perform(get("/attendances/cut-requests").header("Authorization", clientToken))
                .andExpect(status().isForbidden());
    }
}
