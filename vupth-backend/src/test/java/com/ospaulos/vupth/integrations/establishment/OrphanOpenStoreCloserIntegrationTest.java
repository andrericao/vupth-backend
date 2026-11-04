package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.OrphanOpenStoreCloser;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** loja-so-abre-pelo-app (BAK-8, DEC-159): loja gravada OPEN sem ninguem trabalhando fecha; nada abre. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrphanOpenStoreCloserIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository repository;
    @Autowired OrphanOpenStoreCloser closer;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("OPEN sem profissional em expediente fecha; a loja ja fechada segue fechada")
    void closesOrphanOpenStore() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        UUID id = UUID.fromString(estab.id());
        Establishment e = repository.findById(id).orElseThrow();
        e.setOpenStatus(OpenStatus.OPEN);   // estado velho: aberta sem ninguem trabalhando
        repository.save(e);

        int closed = closer.closeOrphans();

        assertThat(closed).isGreaterThanOrEqualTo(1);
        assertThat(repository.findById(id).orElseThrow().getOpenStatus()).isEqualTo(OpenStatus.CLOSED);
        assertThat(closer.closeOrphans()).isZero();
    }
}
