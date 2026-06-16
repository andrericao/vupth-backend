package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.interval.valueObject.IntervalType;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — seed das refeicoes padrao (spec item #11a).
 *
 * Ao criar um profissional (por quick-create OU register) ja nascem almoco (12:00)
 * e janta (18:00), recorrentes, 60min cada, como PREVISAO (isActive=false).
 *
 * Verificacao direto no repositorio: como sao recorrentes inativos, ainda nao
 * aparecem no GET .../intervals (que filtra isActive=true — vira item (d)).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfessionalMealSeedIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    @Test
    @Order(10)
    @DisplayName("quick-create — profissional nasce com almoco 12:00/60 + janta 18:00/60 (previsao)")
    void quickCreate_seedsLunchAndDinner() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());

        // solo shop: o mesmo usuario vira profissional (professionalId == userId via MapsId)
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id());

        assertSeededMeals(UUID.fromString(owner.id()));
    }

    @Test
    @Order(11)
    @DisplayName("register (sem establishment) — profissional nasce com almoco + janta")
    void register_seedsLunchAndDinner() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token())
                        .content("{\"name\":\"Roberto\",\"specialty\":\"Barba\"}"))
                .andExpect(status().isCreated());

        assertSeededMeals(UUID.fromString(user.id()));
    }

    private void assertSeededMeals(UUID professionalId) {
        List<ProfessionalInterval> meals = intervalRepository.findAll().stream()
                .filter(i -> i.getProfessional().getId().equals(professionalId))
                .toList();

        assertThat(meals).hasSize(2);
        assertThat(meals).allSatisfy(i -> {
            assertThat(i.getIntervalType()).isEqualTo(IntervalType.RECURRENT);
            assertThat(i.getDurationMinutes()).isEqualTo(60);
            assertThat(i.getIsActive()).isFalse(); // previsao, nao iniciado
            assertThat(i.getRecurrencePattern()).isEqualTo("WEEKDAYS");
        });

        ProfessionalInterval lunch = meals.stream()
                .filter(i -> i.getFixedStartTime().getHour() == 12)
                .findFirst().orElseThrow();
        ProfessionalInterval dinner = meals.stream()
                .filter(i -> i.getFixedStartTime().getHour() == 18)
                .findFirst().orElseThrow();

        assertThat(lunch.getDescription()).isEqualTo("Lunch Break");
        assertThat(dinner.getDescription()).isEqualTo("Dinner Break");
    }

    @AfterAll
    void tearDown() {
        intervalRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
