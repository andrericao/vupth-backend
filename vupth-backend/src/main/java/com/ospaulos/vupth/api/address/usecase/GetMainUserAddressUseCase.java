package com.ospaulos.vupth.api.address.usecase;

import com.ospaulos.vupth.api.address.dto.response.UserAddressResponse;
import com.ospaulos.vupth.api.address.mapper.UserAddressMapper;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.UserAddress;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetMainUserAddressUseCase {

    private final UserRepository userRepository;
    private final UserAddressMapper userAddressMapper;

    @Transactional
    public UserAddressResponse execute(UUID userId){

        BaseUser user = userRepository.findById(userId)
                .orElseThrow(
                        () -> new UserNotFoundException(userId)
                );

        UserAddress userAddress = user.getMainAddress();

        Address address = userAddress.getAddress();

        return userAddressMapper.toUserAddressResponse(user.getId(), userAddress.getId(), userAddress.getIsMain(), address);
    }
}
