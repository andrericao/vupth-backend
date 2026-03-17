package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.api.user.exception.UserNotDeletedException;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeleteUserUseCase {

    private final UserRepository repository;

    public void executeDeleteUser(UUID id){

        repository.deleteById(id);

        if(repository.existsById(id)){
            throw new UserNotDeletedException(id);
        }
    }
}
