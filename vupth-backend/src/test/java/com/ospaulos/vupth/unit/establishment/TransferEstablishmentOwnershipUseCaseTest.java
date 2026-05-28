package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.api.establishment.usecase.TransferEstablishmentOwnershipUseCase;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.owner.repository.OwnerRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferEstablishmentOwnershipUseCase")
class TransferEstablishmentOwnershipUseCaseTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID NEW_OWNER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID ESTAB_ID = UUID.randomUUID();

    @Mock EstablishmentRepository establishmentRepository;
    @Mock UserRepository userRepository;
    @Mock OwnerRepository ownerRepository;

    @InjectMocks TransferEstablishmentOwnershipUseCase useCase;

    private Establishment establishment;
    private Owner currentOwner;
    private BaseUser newOwnerUser;
    private Owner newOwner;

    @BeforeEach
    void setUp() {
        BaseUser ownerUser = new BaseUser();
        ownerUser.setId(OWNER_ID);
        currentOwner = new Owner(ownerUser);

        establishment = new Establishment();
        establishment.setId(ESTAB_ID);
        establishment.setName("Barbearia");
        establishment.setOwner(currentOwner);
        establishment.setStatus(EstablishmentStatus.ACTIVE);
        establishment.setOpenStatus(OpenStatus.OPEN);
        establishment.setServices(Set.of(ServiceType.BARBER));

        newOwnerUser = new BaseUser();
        newOwnerUser.setId(NEW_OWNER_ID);
        newOwnerUser.addOwnerProfile();
        newOwner = new Owner(newOwnerUser);
    }

    @Nested
    @DisplayName("success cases")
    class SuccessCases {

        @Test
        @DisplayName("success: transfer ownership to user who is already OWNER")
        void execute_success_existingOwner() {
            when(establishmentRepository.findByIdAndOwnerId(ESTAB_ID, OWNER_ID))
                    .thenReturn(Optional.of(establishment));
            when(userRepository.findById(NEW_OWNER_ID)).thenReturn(Optional.of(newOwnerUser));
            when(ownerRepository.findById(NEW_OWNER_ID)).thenReturn(Optional.of(newOwner));
            when(establishmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EstablishmentResponse response = useCase.execute(OWNER_ID, ESTAB_ID, NEW_OWNER_ID);

            assertThat(response.ownerId()).isEqualTo(NEW_OWNER_ID);
            assertThat(establishment.getOwner()).isEqualTo(newOwner);
        }

        @Test
        @DisplayName("success: transfer ownership to user without OWNER profile — auto-creates")
        void execute_success_createsOwnerProfile() {
            BaseUser plainUser = new BaseUser();
            plainUser.setId(NEW_OWNER_ID);

            when(establishmentRepository.findByIdAndOwnerId(ESTAB_ID, OWNER_ID))
                    .thenReturn(Optional.of(establishment));
            when(userRepository.findById(NEW_OWNER_ID)).thenReturn(Optional.of(plainUser));
            when(userRepository.save(any())).thenReturn(plainUser);
            when(ownerRepository.findById(NEW_OWNER_ID)).thenReturn(Optional.of(new Owner(plainUser)));
            when(establishmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EstablishmentResponse response = useCase.execute(OWNER_ID, ESTAB_ID, NEW_OWNER_ID);

            assertThat(response.ownerId()).isEqualTo(NEW_OWNER_ID);
            verify(userRepository).save(plainUser);
        }
    }

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("establishment not found or not owned → EstablishmentNotFoundException")
        void execute_notFound() {
            when(establishmentRepository.findByIdAndOwnerId(ESTAB_ID, OWNER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, NEW_OWNER_ID))
                    .isInstanceOf(EstablishmentNotFoundException.class);
        }

        @Test
        @DisplayName("new owner user not found → UserNotFoundException")
        void execute_userNotFound() {
            when(establishmentRepository.findByIdAndOwnerId(ESTAB_ID, OWNER_ID))
                    .thenReturn(Optional.of(establishment));
            when(userRepository.findById(NEW_OWNER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(OWNER_ID, ESTAB_ID, NEW_OWNER_ID))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }
}
