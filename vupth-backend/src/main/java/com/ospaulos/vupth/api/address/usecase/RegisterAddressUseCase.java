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
import com.ospaulos.vupth.domain.address.valueObject.AddressCoordinates;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.UserAddress;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

        // Mesmo portao do endereco do estabelecimento (fatia A2): o DTO e o mesmo e ja aceitava
        // lat/lng, entao um par invalido entrava aqui tambem.
        AddressCoordinates.validate(request.latitude(), request.longitude());

            BaseUser user = userRepository.findById(id)
                    .orElseThrow(
                            () -> new UserNotFoundException(id)
                    );

        Address address = addressMapper.toAddressEntity(request);
        address.markGpsIfCoordinatesSent(request.latitude(), request.longitude());

        Set<UUID> existingIds = user.getUserAddresses().stream()
                .map(UserAddress::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        UserAddress draft = user.addAddress(address);

        BaseUser userCreated = userRepository.save(user);

        // save() faz merge: o endereco salvo (com id) e a copia que esta na colecao do usuario devolvido,
        // nao o rascunho — no Hibernate 6.6 o rascunho fica sem id.
        UserAddress userAddress = userCreated.getUserAddresses().stream()
                .filter(ua -> ua.getId() != null && !existingIds.contains(ua.getId()))
                .findFirst()
                .orElse(draft);

        UserAddressResponse userAddressResponse = userAddressMapper.toUserAddressResponse(
                user.getId(), userAddress.getId(), userAddress.getIsMain(), userAddress.getAddress());
        UserProfileGeneralInfosResponse userResponse = userMapper.toGeneralInfosUserResponse(userCreated);

        return new UserAddressAndGeneralInfosResponse(userResponse, userAddressResponse);
    }
}
