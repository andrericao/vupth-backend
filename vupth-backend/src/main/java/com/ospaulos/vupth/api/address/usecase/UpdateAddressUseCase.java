package com.ospaulos.vupth.api.address.usecase;

import com.ospaulos.vupth.api.address.dto.request.UpdateAddressRequest;
import com.ospaulos.vupth.api.address.dto.response.UserAddressResponse;
import com.ospaulos.vupth.api.address.mapper.UserAddressMapper;
import com.ospaulos.vupth.api.address.mapper.impl.UserAddressManualMapper;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.dto.response.useraddress.UserAddressAndGeneralInfosResponse;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
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
public class UpdateAddressUseCase {

    private final UserRepository userRepository;
    private final UserAddressMapper userAddressMapper;
    private final UserMapper userMapper;
    private final UserAddressManualMapper userAddressManualMapper;

    @Transactional
    public UserAddressAndGeneralInfosResponse execute(UpdateAddressRequest request, UUID id){
        log.info("USECASE UPDATE ADDRESS");

        BaseUser user = userRepository.findById(id)
                .orElseThrow(
                        () -> new UserNotFoundException(id)
                );

        UserAddress userAddress = user.getUserAddress(request.userAddressId());

        user.updateUserAddress(request);

        UserProfileGeneralInfosResponse userResponse = userMapper.toGeneralInfosUserResponse(user);

        UserAddressResponse addressResponse = userAddressManualMapper.toUserAddressResponse(userAddress);

        return new UserAddressAndGeneralInfosResponse(userResponse, addressResponse);
    }
}
