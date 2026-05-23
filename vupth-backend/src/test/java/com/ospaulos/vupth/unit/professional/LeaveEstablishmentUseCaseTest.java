package com.ospaulos.vupth.unit.professional;

import com.ospaulos.vupth.api.professional.usecase.LeaveEstablishmentUseCase;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LeaveEstablishmentUseCase")
class LeaveEstablishmentUseCaseTest {

    private static final UUID PROF_ID = UUID.randomUUID();
    private static final UUID ESTAB_ID = UUID.randomUUID();

    @Mock ProfessionalRepository professionalRepository;
    @Mock AttendanceRepository attendanceRepository;

    @InjectMocks LeaveEstablishmentUseCase useCase;

    private Professional professional;

    @BeforeEach
    void setUp() {
        BaseUser profUser = new BaseUser();
        profUser.setId(PROF_ID);
        profUser.addProfessionalProfile();
        professional = profUser.getProfessional();

        Establishment establishment = new Establishment();
        establishment.setId(ESTAB_ID);
        professional.setEstablishment(establishment);
    }

    @Nested
    @DisplayName("success cases")
    class SuccessCases {

        @Test
        @DisplayName("success: professional leaves establishment")
        void execute_success() {
            when(professionalRepository.findById(PROF_ID)).thenReturn(Optional.of(professional));
            when(attendanceRepository.existsByProfessional_IdAndStatusIn(eq(PROF_ID), any()))
                    .thenReturn(false);

            useCase.execute(PROF_ID);

            assertThat(professional.getEstablishment()).isNull();
            verify(professionalRepository).save(professional);
        }

        @Test
        @DisplayName("success: professional already without establishment — no-op")
        void execute_alreadyNoEstablishment() {
            professional.setEstablishment(null);
            when(professionalRepository.findById(PROF_ID)).thenReturn(Optional.of(professional));

            useCase.execute(PROF_ID);

            verify(attendanceRepository, never()).existsByProfessional_IdAndStatusIn(any(), any());
            verify(professionalRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("professional not found → ProfessionalNotFoundException")
        void execute_notFound() {
            when(professionalRepository.findById(PROF_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(PROF_ID))
                    .isInstanceOf(ProfessionalNotFoundException.class);
        }

        @Test
        @DisplayName("professional has active attendances → ProfessionalHasActiveAttendancesException")
        void execute_hasActiveAttendances() {
            when(professionalRepository.findById(PROF_ID)).thenReturn(Optional.of(professional));
            when(attendanceRepository.existsByProfessional_IdAndStatusIn(eq(PROF_ID), any()))
                    .thenReturn(true);

            assertThatThrownBy(() -> useCase.execute(PROF_ID))
                    .isInstanceOf(ProfessionalHasActiveAttendancesException.class);

            assertThat(professional.getEstablishment()).isNotNull();
            verify(professionalRepository, never()).save(any());
        }
    }
}
