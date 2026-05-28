package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.api.establishment.usecase.RemoveProfessionalFromEstablishmentUseCase;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalHasActiveAttendancesException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RemoveProfessionalFromEstablishmentUseCase")
class RemoveProfessionalFromEstablishmentUseCaseTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID OTHER_OWNER_ID = UUID.randomUUID();
    private static final UUID ESTAB_ID = UUID.randomUUID();
    private static final UUID PROF_ID = UUID.randomUUID();

    @Mock EstablishmentRepository establishmentRepository;
    @Mock ProfessionalRepository professionalRepository;
    @Mock AttendanceRepository attendanceRepository;

    @InjectMocks RemoveProfessionalFromEstablishmentUseCase useCase;

    private Establishment establishment;
    private Professional professional;

    @BeforeEach
    void setUp() {
        BaseUser ownerUser = new BaseUser();
        ownerUser.setId(OWNER_ID);
        Owner owner = new Owner(ownerUser);

        establishment = new Establishment();
        establishment.setId(ESTAB_ID);
        establishment.setName("Barbearia");
        establishment.setOwner(owner);

        BaseUser profUser = new BaseUser();
        profUser.setId(PROF_ID);
        profUser.addProfessionalProfile();
        professional = profUser.getProfessional();
        professional.setEstablishment(establishment);
    }

    @Nested
    @DisplayName("success cases")
    class SuccessCases {

        @Test
        @DisplayName("success: owner removes professional with no active attendances")
        void execute_success() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findById(PROF_ID)).thenReturn(Optional.of(professional));
            when(attendanceRepository.existsByProfessional_IdAndStatusIn(eq(PROF_ID), any()))
                    .thenReturn(false);

            useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID);

            assertThat(professional.getEstablishment()).isNull();
            verify(professionalRepository).save(professional);
        }
    }

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("establishment not found → EstablishmentNotFoundException")
        void execute_establishmentNotFound() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(EstablishmentNotFoundException.class);
        }

        @Test
        @DisplayName("wrong owner → EstablishmentAccessDeniedException")
        void execute_wrongOwner() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));

            assertThatThrownBy(() -> useCase.execute(OTHER_OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(EstablishmentAccessDeniedException.class);
        }

        @Test
        @DisplayName("professional not found → ProfessionalNotFoundException")
        void execute_professionalNotFound() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findById(PROF_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(ProfessionalNotFoundException.class);
        }

        @Test
        @DisplayName("professional not in this establishment → ProfessionalNotFoundException")
        void execute_professionalNotInEstablishment() {
            Establishment otherEstab = new Establishment();
            otherEstab.setId(UUID.randomUUID());
            professional.setEstablishment(otherEstab);

            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findById(PROF_ID)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(ProfessionalNotFoundException.class);
        }

        @Test
        @DisplayName("professional has active attendances → ProfessionalHasActiveAttendancesException")
        void execute_hasActiveAttendances() {
            when(establishmentRepository.findByIdEager(ESTAB_ID)).thenReturn(Optional.of(establishment));
            when(professionalRepository.findById(PROF_ID)).thenReturn(Optional.of(professional));
            when(attendanceRepository.existsByProfessional_IdAndStatusIn(eq(PROF_ID), any()))
                    .thenReturn(true);

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, PROF_ID))
                    .isInstanceOf(ProfessionalHasActiveAttendancesException.class);

            verify(professionalRepository, never()).save(any());
        }
    }
}
