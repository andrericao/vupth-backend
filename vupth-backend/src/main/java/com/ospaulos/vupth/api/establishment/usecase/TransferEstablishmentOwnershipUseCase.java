package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.owner.repository.OwnerRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferEstablishmentOwnershipUseCase {

    private final EstablishmentRepository establishmentRepository;
    private final UserRepository userRepository;
    private final OwnerRepository ownerRepository;

    @Transactional
    public EstablishmentResponse execute(UUID currentOwnerId, UUID establishmentId, UUID newOwnerId) {
        log.info("[Establishment] transferOwnership — id={} from={} to={}",
                establishmentId, currentOwnerId, newOwnerId);

        Establishment establishment = establishmentRepository
                .findByIdAndOwnerId(establishmentId, currentOwnerId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        BaseUser newOwnerUser = userRepository.findById(newOwnerId)
                .orElseThrow(() -> new UserNotFoundException(newOwnerId));

        if (!newOwnerUser.hasRole(RoleType.OWNER)) {
            newOwnerUser.addOwnerProfile();
            userRepository.save(newOwnerUser);
            log.info("Perfil OWNER criado automaticamente para user={}", newOwnerId);
        }

        Owner newOwner = ownerRepository.findById(newOwnerId)
                .orElseThrow(() -> new RuntimeException("Owner profile not found after creation"));

        establishment.setOwner(newOwner);
        Establishment saved = establishmentRepository.save(establishment);

        log.info("[Establishment] ownership transferred — id={} newOwner={}", establishmentId, newOwnerId);

        return EstablishmentResponse.fromEntity(saved);
    }
}
