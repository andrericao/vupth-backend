package com.ospaulos.vupth.unit.serviceitem;

import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.service.impl.ServiceItemServiceImpl;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests — ServiceItemServiceImpl
 *
 * Covers:
 *
 * createServiceItem:
 * - Success: ServiceItem created and linked to the Professional
 * - ServiceItem is linked to Professional, not directly to Establishment
 *
 * avarageCalculateLastWeek:
 * - No FINISHED attendances this week → returns 0
 * - With attendances → returns average of non-null durations
 * - Attendances with null duration → ignored
 * - All null durations → returns 0
 *
 * Business rules:
 * - ServiceItem is linked to Professional (Establishment accessed via professional.establishment)
 * - Weekly average uses only FINISHED attendances
 *
 * @since v5
 */
@ExtendWith(MockitoExtension.class)
class ServiceItemServiceImplTest {

    @Mock ServiceItemRepository serviceItemRepository;
    @Mock AttendanceRepository attendanceRepository;

    /**
     * Declared only to satisfy @InjectMocks — these dependencies exist in
     * ServiceItemServiceImpl's constructor but are not exercised by the methods tested here.
     *
     * @compat Reflects ServiceItemServiceImpl constructor as of v5.
     *         If new dependencies are added to the service, revisit this class.
     */
    @Mock com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository professionalRepository;
    @Mock com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository establishmentRepository;

    @InjectMocks ServiceItemServiceImpl serviceItemService;

    private Professional professional;
    private Establishment establishment;
    private UUID serviceItemId;

    @BeforeEach
    void setUp() {
        serviceItemId = UUID.randomUUID();

        establishment = new Establishment();
        establishment.setId(UUID.randomUUID());
        establishment.setName("Test Barbershop");

        BaseUser profUser = new BaseUser();
        profUser.setId(UUID.randomUUID());
        profUser.setFirstName("Carlos");

        professional = new Professional(profUser);
        professional.setEstablishment(establishment);
        professional.setIsAvailable(true);
    }

    // ─────────────────────────────────────────────
    // createServiceItem
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("createServiceItem")
    class CreateServiceItemTests {

        @Test
        @DisplayName("success: ServiceItem created with correct data")
        void create_success() {
            ServiceItemRequest request = new ServiceItemRequest(
                    establishment.getId(),
                    ServiceItemType.CORTE_MAQUINA,
                    "Fade Haircut",
                    BigDecimal.valueOf(35),
                    30, null, null);

            ServiceItem savedItem = new ServiceItem();
            savedItem.setCustomName("Fade Haircut");
            savedItem.setPrice(BigDecimal.valueOf(35));
            savedItem.setServiceItemType(ServiceItemType.CORTE_MAQUINA);
            savedItem.setProfessional(professional);

            when(serviceItemRepository.save(any(ServiceItem.class))).thenReturn(savedItem);

            ServiceItemResponse result = serviceItemService.createServiceItem(professional, request);

            assertThat(result).isNotNull();
            assertThat(result.customName()).isEqualTo("Fade Haircut");
            assertThat(result.price()).isEqualByComparingTo(BigDecimal.valueOf(35));
            assertThat(result.serviceItemType()).isEqualTo(ServiceItemType.CORTE_MAQUINA);
            verify(serviceItemRepository).save(any(ServiceItem.class));
        }

        /**
         * Verifies that the ServiceItem is linked to the correct Professional instance.
         *
         * @compat Uses CORTE_MAQUINA as a stand-in. The ServiceItemType enum (v5) does not
         *         yet include a dedicated beard/trim type. When that value is added,
         *         update this test to use the specific type.
         */
        @Test
        @DisplayName("ServiceItem is linked to the correct professional")
        void create_linkedToProfessional() {
            // CORTE_MAQUINA used as stand-in — see @compat above
            ServiceItemRequest request = new ServiceItemRequest(
                    establishment.getId(), ServiceItemType.CORTE_MAQUINA, "Beard Trim", BigDecimal.valueOf(20), 30, null, null);

            when(serviceItemRepository.save(any())).thenAnswer(inv -> {
                ServiceItem si = inv.getArgument(0);
                assertThat(si.getProfessional()).isEqualTo(professional);
                return si;
            });

            serviceItemService.createServiceItem(professional, request);

            verify(serviceItemRepository).save(argThat(si -> si.getProfessional() == professional));
        }
    }

    // ─────────────────────────────────────────────
    // avarageCalculateLastWeek
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("avarageCalculateLastWeek")
    class AverageCalculationTests {

        @Test
        @DisplayName("no FINISHED attendances this week → returns 0")
        void average_noAttendances_returnsZero() {
            when(attendanceRepository.findByServiceItems_IdAndStatusAndCreatedAtAfter(
                    eq(serviceItemId), eq(AttendanceStatus.FINISHED), any()))
                    .thenReturn(List.of());

            double result = serviceItemService.avarageCalculateLastWeek(serviceItemId);

            assertThat(result).isEqualTo(0.0);
        }

        @Test
        @DisplayName("with attendances → returns mean of non-null durations")
        void average_withAttendances_returnsMean() {
            Attendance a1 = new Attendance(); a1.setDurationInMinutes(30);
            Attendance a2 = new Attendance(); a2.setDurationInMinutes(45);
            Attendance a3 = new Attendance(); a3.setDurationInMinutes(15);

            when(attendanceRepository.findByServiceItems_IdAndStatusAndCreatedAtAfter(
                    eq(serviceItemId), eq(AttendanceStatus.FINISHED), any()))
                    .thenReturn(List.of(a1, a2, a3));

            double result = serviceItemService.avarageCalculateLastWeek(serviceItemId);

            assertThat(result).isEqualTo(30.0); // average of 30, 45, 15
        }

        @Test
        @DisplayName("attendances with null duration are ignored")
        void average_nullDurationsIgnored() {
            Attendance withDuration = new Attendance();    withDuration.setDurationInMinutes(60);
            Attendance withoutDuration = new Attendance(); withoutDuration.setDurationInMinutes(null);

            when(attendanceRepository.findByServiceItems_IdAndStatusAndCreatedAtAfter(
                    eq(serviceItemId), eq(AttendanceStatus.FINISHED), any()))
                    .thenReturn(List.of(withDuration, withoutDuration));

            double result = serviceItemService.avarageCalculateLastWeek(serviceItemId);

            assertThat(result).isEqualTo(60.0);
        }

        @Test
        @DisplayName("all attendances have null duration → returns 0")
        void average_allNullDurations_returnsZero() {
            Attendance a1 = new Attendance(); a1.setDurationInMinutes(null);

            when(attendanceRepository.findByServiceItems_IdAndStatusAndCreatedAtAfter(
                    eq(serviceItemId), eq(AttendanceStatus.FINISHED), any()))
                    .thenReturn(List.of(a1));

            double result = serviceItemService.avarageCalculateLastWeek(serviceItemId);

            assertThat(result).isEqualTo(0.0);
        }
    }
}
