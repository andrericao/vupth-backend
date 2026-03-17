package com.ospaulos.vupth.unit.owner;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.owner.service.impl.OwnerService;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import jakarta.persistence.EntityNotFoundException;
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
import static org.mockito.Mockito.*;

/**
 * Unit tests — OwnerService
 *
 * Business rules for addProfessional:
 * - Returns true when the professional is successfully added to the establishment.
 * - Returns false when the user is already an OWNER (owners cannot be added as professionals).
 * - Throws EntityNotFoundException when the establishment does not exist.
 *
 * @since v9
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OwnerService")
class OwnerServiceTest {

    @Mock EstablishmentRepository establishmentRepository;

    @InjectMocks OwnerService ownerService;

    private UUID establishmentId;
    private BaseUser baseUser;
    private Professional professional;
    private Establishment establishment;

    @BeforeEach
    void setUp() {
        establishmentId = UUID.randomUUID();

        baseUser = new BaseUser();
        baseUser.setId(UUID.randomUUID());
        baseUser.setEmail("professional@example.com");
        baseUser.setPassword("$2a$10$hashedPasswordMock");
        baseUser.addClientProfile();
        baseUser.addProfessionalProfile();

        professional = new Professional(baseUser);

        establishment = new Establishment();
        establishment.setId(establishmentId);
        establishment.setName("Test Barbershop");
    }

    // ─────────────────────────────────────────────────────────────
    // addProfessional — happy path
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addProfessional")
    class AddProfessionalTests {

        @Test
        @DisplayName("success: professional added to establishment, returns true")
        void addProfessional_success_returnsTrue() {
            when(establishmentRepository.findById(establishmentId))
                    .thenReturn(Optional.of(establishment));

            boolean result = ownerService.addProfessional(establishmentId, professional);

            assertThat(result).isTrue();
            assertThat(establishment.getProfessionals()).contains(professional);
            verify(establishmentRepository).findById(establishmentId);
        }

        @Test
        @DisplayName("user is OWNER and PROFESSIONAL: can be added as professional of own establishment (manicure solo case)")
        void addProfessional_userIsOwnerAndProfessional_canBeAdded() {
            // Business rule: an OWNER can also register themselves as a PROFESSIONAL.
            // Example: a solo manicurist who owns and works in their own home studio.
            // OWNER → PROFESSIONAL is allowed. PROFESSIONAL → OWNER is NOT allowed.
            baseUser.addOwnerProfile();
            assertThat(baseUser.isOwner()).isTrue();

            when(establishmentRepository.findById(establishmentId))
                    .thenReturn(Optional.of(establishment));

            boolean result = ownerService.addProfessional(establishmentId, professional);

            assertThat(result).isTrue();
            assertThat(establishment.getProfessionals()).contains(professional);
        }

        @Test
        @DisplayName("establishment not found: throws EntityNotFoundException")
        void addProfessional_establishmentNotFound_throws() {
            when(establishmentRepository.findById(any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    ownerService.addProfessional(establishmentId, professional))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Establishment not found");
        }

        @Test
        @DisplayName("professional added: establishment professionals list grows by one")
        void addProfessional_listsizeGrowsByOne() {
            assertThat(establishment.getProfessionals()).isEmpty();

            when(establishmentRepository.findById(establishmentId))
                    .thenReturn(Optional.of(establishment));

            ownerService.addProfessional(establishmentId, professional);

            assertThat(establishment.getProfessionals()).hasSize(1);
        }
    }
}
