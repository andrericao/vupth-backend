package com.ospaulos.vupth.api.address.usecase;

import com.ospaulos.vupth.api.address.dto.response.UserAddressResponse;
import com.ospaulos.vupth.api.address.mapper.UserAddressMapper;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.UserAddress;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetAllAddressUseCase {

    private final UserRepository userRepository;
    private final UserAddressMapper userAddressMapper;

    @Transactional
    public List<UserAddressResponse> execute(UUID userId){

        BaseUser user = userRepository.findById(userId)
                .orElseThrow(
                        () -> new UserNotFoundException(userId)
                );

        List<UserAddress> addresses = user.getUserAddresses();

        return addresses.stream()
                .map(
                userAddress -> userAddressMapper.toUserAddressResponse(
                        user.getId(),
                        userAddress.getId(),
                        userAddress.getIsMain(),
                        userAddress.getAddress())
        ).toList();
    }

}
