package com.ospaulos.vupth.domain.user.service.impl;

import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.mapper.AddressMapper;
import com.ospaulos.vupth.api.address.mapper.UserAddressMapper;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.dto.response.useraddress.UserAddressAndGeneralInfosResponse;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.user.model.UserAddress;
import com.ospaulos.vupth.domain.user.service.UserAddressService;
import com.ospaulos.vupth.api.address.dto.response.UserAddressResponse;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
public class UserAddressServiceImpl implements UserAddressService {

    private final UserRepository userRepository;
    private final AddressMapper addressMapper;
    private final UserAddressMapper userAddressMapper;
    private final UserMapper userMapper;

    @Override
    public UserAddressAndGeneralInfosResponse registerAddress(AddressRequest request, UUID userId){

        log.info("SERVICE CREATE USER ADDRESS");

        BaseUser user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        Address address = addressMapper.toAddressEntity(request);
        address.markGpsIfCoordinatesSent(request.latitude(), request.longitude());

        UserAddress userAddress = user.addAddress(address);

        BaseUser userSaved = userRepository.save(user);

        UserProfileGeneralInfosResponse userResponse = userMapper.toGeneralInfosUserResponse(userSaved);
        UserAddressResponse addressResponse = userAddressMapper.toUserAddressResponse(user.getId(), userAddress.getId(), userAddress.getIsMain(), address);

        return new UserAddressAndGeneralInfosResponse(userResponse, addressResponse);
    }
}
