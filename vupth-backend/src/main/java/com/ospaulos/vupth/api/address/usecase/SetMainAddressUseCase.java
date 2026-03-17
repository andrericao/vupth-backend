package com.ospaulos.vupth.api.address.usecase;

import com.ospaulos.vupth.api.user.dto.response.useraddress.UserAddressAndGeneralInfosResponse;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.UserAddress;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SetMainAddressUseCase {

    // TODO: Improve exception
    private final UserRepository userRepository;

    @Transactional
    public String execute(UUID userId, UUID userAddressId){

        BaseUser user = userRepository.findById(userId)
                .orElseThrow(
                        () -> new UserNotFoundException(userId)
                );

        user.setMainAddress(userAddressId);

        return "Address updated do MAIN!";
    }
}
