package com.ospaulos.vupth.api.address.usecase;

import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.dto.response.AddressResponse;
import com.ospaulos.vupth.api.address.dto.response.UserAddressResponse;
import com.ospaulos.vupth.api.address.mapper.AddressMapper;
import com.ospaulos.vupth.api.address.mapper.UserAddressMapper;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.dto.response.useraddress.UserAddressAndGeneralInfosResponse;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
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
public class RegisterAddressUseCase {

    private final UserRepository userRepository;
    private final AddressMapper addressMapper;
    private final UserAddressMapper userAddressMapper;
    private final UserMapper userMapper;

    @Transactional
    public UserAddressAndGeneralInfosResponse execute(AddressRequest request, UUID id){

        log.info("USECASE REGISTER ADDRESS");

            BaseUser user = userRepository.findById(id)
                    .orElseThrow(
                            () -> new UserNotFoundException(id)
                    );

        Address address = addressMapper.toAddressEntity(request);

        UserAddress userAddress = user.addAddress(address);

        BaseUser userCreated = userRepository.save(user);

        UserAddressResponse userAddressResponse = userAddressMapper.toUserAddressResponse(user.getId(), userAddress.getId(), userAddress.getIsMain(), address);
        UserProfileGeneralInfosResponse userResponse = userMapper.toGeneralInfosUserResponse(userCreated);

        return new UserAddressAndGeneralInfosResponse(userResponse, userAddressResponse);
    }
}
