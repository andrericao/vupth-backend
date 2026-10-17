package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.impl.EstablishmentOpenStatusServiceImpl;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * deriveFromProfessionals (S1) sem banco: aberto se ha alguem trabalhando; o CLOSED derivado nao rebaixa um
 * TEMPORARILY_CLOSED manual; sem mudanca, nada e gravado. A abertura real com horario configurado fica no
 * RealOpeningTimeIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class EstablishmentOpenStatusServiceImplTest {

    @Mock EstablishmentRepository establishmentRepository;
    @Mock ProfessionalRepository professionalRepository;
    @InjectMocks EstablishmentOpenStatusServiceImpl service;

    private UUID id;
    private Establishment establishment;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        establishment = new Establishment();
        establishment.setId(id);
        when(establishmentRepository.findById(id)).thenReturn(Optional.of(establishment));
    }

    private void anyoneWorking(boolean working) {
        when(professionalRepository.existsByEstablishment_IdAndWorkStatusIn(eq(id), anyList())).thenReturn(working);
    }

    @Test
    @DisplayName("alguem trabalhando e loja fechada: abre e grava (sem horario configurado, sem excecao do dia)")
    void opens() {
        establishment.setOpenStatus(OpenStatus.CLOSED);
        anyoneWorking(true);

        assertThat(service.deriveFromProfessionals(id)).isTrue();
        assertThat(establishment.getOpenStatus()).isEqualTo(OpenStatus.OPEN);
        assertThat(establishment.getLastStatusChange()).isNotNull();
        verify(establishmentRepository).save(establishment);
    }

    @Test
    @DisplayName("ninguem trabalhando e loja aberta: fecha")
    void closes() {
        establishment.setOpenStatus(OpenStatus.OPEN);
        anyoneWorking(false);

        assertThat(service.deriveFromProfessionals(id)).isTrue();
        assertThat(establishment.getOpenStatus()).isEqualTo(OpenStatus.CLOSED);
    }

    @Test
    @DisplayName("ja no estado derivado: nada muda, nada e gravado")
    void unchanged() {
        establishment.setOpenStatus(OpenStatus.OPEN);
        anyoneWorking(true);

        assertThat(service.deriveFromProfessionals(id)).isFalse();
        verify(establishmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("TEMPORARILY_CLOSED manual e ninguem trabalhando: continua TEMPORARILY_CLOSED")
    void keepsTemporarilyClosed() {
        establishment.setOpenStatus(OpenStatus.TEMPORARILY_CLOSED);
        anyoneWorking(false);

        assertThat(service.deriveFromProfessionals(id)).isFalse();
        assertThat(establishment.getOpenStatus()).isEqualTo(OpenStatus.TEMPORARILY_CLOSED);
        verify(establishmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("TEMPORARILY_CLOSED manual e alguem chega: abre")
    void opensOverTemporarilyClosed() {
        establishment.setOpenStatus(OpenStatus.TEMPORARILY_CLOSED);
        anyoneWorking(true);

        assertThat(service.deriveFromProfessionals(id)).isTrue();
        assertThat(establishment.getOpenStatus()).isEqualTo(OpenStatus.OPEN);
    }
}
