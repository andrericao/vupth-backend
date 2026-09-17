package com.ospaulos.vupth.unit.serviceitem;

import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.request.UpdateServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.serviceItem.exception.InvalidPriceSplitException;
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
import java.util.Optional;
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
 * precificacao (Receita LIQUIDA, 16/09):
 * - pro_receives valido e gravado, e a resposta traz a divisao
 * - pro_receives que viola a politica -> InvalidPriceSplitException, nada salvo
 * - sem pro_receives: servico legado, divisao nula
 * - PATCH preco + recebe; so recebe (valida contra o preco atual); so preco novo (apaga o recebe)
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

    // ─────────────────────────────────────────────
    // precificacao — pro_receives e a divisao do preco
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("precificacao (pro_receives)")
    class PricingTests {

        private ServiceItemRequest createRequest(String price, String proReceives, String base) {
            return new ServiceItemRequest(
                    establishment.getId(), ServiceItemType.CORTE_MAQUINA, "Corte",
                    new BigDecimal(price), 30, null, null,
                    proReceives == null ? null : new BigDecimal(proReceives),
                    base == null ? null : new BigDecimal(base));
        }

        private ServiceItem existingItem(String price, String proReceives, String base) {
            ServiceItem item = new ServiceItem();
            item.setId(serviceItemId);
            item.setCustomName("Corte");
            item.setServiceItemType(ServiceItemType.CORTE_MAQUINA);
            item.setProfessional(professional);
            item.reprice(new BigDecimal(price),
                    proReceives == null ? null : new BigDecimal(proReceives),
                    base == null ? null : new BigDecimal(base));
            return item;
        }

        private UpdateServiceItemRequest updateRequest(String price, String proReceives, String base) {
            return new UpdateServiceItemRequest(null,
                    price == null ? null : new BigDecimal(price), null, null, null,
                    proReceives == null ? null : new BigDecimal(proReceives),
                    base == null ? null : new BigDecimal(base));
        }

        @BeforeEach
        void saveReturnsArgument() {
            lenient().when(serviceItemRepository.save(any(ServiceItem.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("create com pro_receives valido: grava e devolve a divisao")
        void create_withProReceives_returnsSplit() {
            ServiceItemResponse result = serviceItemService.createServiceItem(professional, createRequest("31.90", "30.00", "30.00"));

            assertThat(result.proReceives()).isEqualByComparingTo("30.00");
            assertThat(result.intermediaryFee()).isEqualByComparingTo("1.20");
            assertThat(result.platformFee()).isEqualByComparingTo("0.70");
            verify(serviceItemRepository).save(argThat(si -> new BigDecimal("30.00").compareTo(si.getProReceives()) == 0));
        }

        @Test
        @DisplayName("create com preco fora da regra: recusa e nao salva")
        void create_invalidSplit_throwsAndDoesNotSave() {
            assertThatThrownBy(() -> serviceItemService.createServiceItem(professional, createRequest("30.90", "30.00", "30.00")))
                    .isInstanceOf(InvalidPriceSplitException.class);
            verify(serviceItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("create sem pro_receives: servico legado, sem divisao")
        void create_withoutProReceives_hasNoSplit() {
            ServiceItemResponse result = serviceItemService.createServiceItem(professional, createRequest("35.00", null, null));

            assertThat(result.price()).isEqualByComparingTo("35.00");
            assertThat(result.proReceives()).isNull();
            assertThat(result.intermediaryFee()).isNull();
            assertThat(result.platformFee()).isNull();
        }

        @Test
        @DisplayName("PATCH preco + recebe: grava os dois")
        void update_priceAndProReceives() {
            ServiceItem item = existingItem("31.90", "30.00", "30.00");
            when(serviceItemRepository.findById(serviceItemId)).thenReturn(Optional.of(item));

            ServiceItemResponse result = serviceItemService.updateServiceItem(
                    professional.getId(), professional.getId(), serviceItemId, updateRequest("53.90", "50.00", "50.00"));

            assertThat(result.price()).isEqualByComparingTo("53.90");
            assertThat(result.proReceives()).isEqualByComparingTo("50.00");
            assertThat(result.platformFee()).isEqualByComparingTo("1.90");
        }

        @Test
        @DisplayName("PATCH so a divisao: conferida contra o preco atual — legado entra na regra")
        void update_onlyProReceives_validatesAgainstCurrentPrice() {
            ServiceItem item = existingItem("31.90", null, null);
            when(serviceItemRepository.findById(serviceItemId)).thenReturn(Optional.of(item));

            ServiceItemResponse result = serviceItemService.updateServiceItem(
                    professional.getId(), professional.getId(), serviceItemId, updateRequest(null, "30.00", "30.00"));

            assertThat(result.proReceives()).isEqualByComparingTo("30.00");
        }

        @Test
        @DisplayName("PATCH so a divisao, fora da regra do preco atual: recusa, nada muda")
        void update_onlyProReceives_invalid_throws() {
            ServiceItem item = existingItem("31.90", "30.00", "30.00");
            when(serviceItemRepository.findById(serviceItemId)).thenReturn(Optional.of(item));

            assertThatThrownBy(() -> serviceItemService.updateServiceItem(
                    professional.getId(), professional.getId(), serviceItemId, updateRequest(null, "31.00", "31.00")))
                    .isInstanceOf(InvalidPriceSplitException.class);
            assertThat(item.getProReceives()).isEqualByComparingTo("30.00");
            verify(serviceItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("PATCH so preco NOVO: o recebe antigo nao vale mais e e apagado")
        void update_onlyNewPrice_clearsProReceives() {
            ServiceItem item = existingItem("31.90", "30.00", "30.00");
            when(serviceItemRepository.findById(serviceItemId)).thenReturn(Optional.of(item));

            ServiceItemResponse result = serviceItemService.updateServiceItem(
                    professional.getId(), professional.getId(), serviceItemId, updateRequest("40.90", null, null));

            assertThat(result.price()).isEqualByComparingTo("40.90");
            assertThat(result.proReceives()).isNull();
            assertThat(result.platformFee()).isNull();
        }

        @Test
        @DisplayName("PATCH com o MESMO preco e sem recebe: mantem o recebe")
        void update_samePrice_keepsProReceives() {
            ServiceItem item = existingItem("31.90", "30.00", "30.00");
            when(serviceItemRepository.findById(serviceItemId)).thenReturn(Optional.of(item));

            ServiceItemResponse result = serviceItemService.updateServiceItem(
                    professional.getId(), professional.getId(), serviceItemId, updateRequest("31.9", null, null));

            assertThat(result.proReceives()).isEqualByComparingTo("30.00");
        }
    }
}
