package com.ospaulos.vupth.unit.user;

import com.ospaulos.vupth.domain.shared.storage.ImageStorage;
import com.ospaulos.vupth.domain.user.exception.InvalidProfilePhotoException;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.domain.user.service.ProfilePhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit tests — foto de perfil (S22; Cloudflare R2, Andre 25/09). */
@ExtendWith(MockitoExtension.class)
class ProfilePhotoServiceTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0};

    @Mock UserRepository userRepository;
    @Mock ImageStorage imageStorage;
    @InjectMocks ProfilePhotoService service;

    UUID userId = UUID.randomUUID();
    BaseUser user;

    @BeforeEach
    void setup() {
        user = new BaseUser();
        user.setId(userId);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(imageStorage.store(anyString(), any(), anyString()))
                .thenAnswer(inv -> "https://fotos.example/" + inv.getArgument(0));
    }

    @Test
    @DisplayName("PNG, JPEG e WebP sao aceitos, pelo tipo dos bytes, com a extensao certa na chave")
    void acceptedTypes() {
        assertThat(service.upload(userId, PNG)).matches("https://fotos.example/profile/" + userId + "/.+\\.png");
        assertThat(service.upload(userId, JPEG)).endsWith(".jpg");
        assertThat(service.upload(userId, WEBP)).endsWith(".webp");
        verify(imageStorage).store(argThat(k -> k.endsWith(".webp")), eq(WEBP), eq("image/webp"));
    }

    @Test
    @DisplayName("o que nao e imagem e recusado — o nome e o Content-Type do cliente nao valem")
    void notAnImage_rejected() {
        assertThatThrownBy(() -> service.upload(userId, "<html>".getBytes()))
                .isInstanceOf(InvalidProfilePhotoException.class);
        verifyNoInteractions(imageStorage);
    }

    @Test
    @DisplayName("vazia ou acima de 2 MB: recusada")
    void emptyOrTooBig_rejected() {
        assertThatThrownBy(() -> service.upload(userId, new byte[0])).isInstanceOf(InvalidProfilePhotoException.class);
        byte[] big = new byte[ProfilePhotoService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThatThrownBy(() -> service.upload(userId, big)).isInstanceOf(InvalidProfilePhotoException.class);
    }

    @Test
    @DisplayName("trocar a foto grava a nova e apaga a anterior")
    void replace_deletesPrevious() {
        user.setProfileImageUrl("https://fotos.example/profile/old.png");

        String url = service.upload(userId, PNG);

        assertThat(user.getProfileImageUrl()).isEqualTo(url);
        verify(imageStorage).deleteByUrl("https://fotos.example/profile/old.png");
    }

    @Test
    @DisplayName("falha ao apagar a foto velha nao desfaz a nova")
    void deleteFailure_ignored() {
        user.setProfileImageUrl("https://fotos.example/profile/old.png");
        doThrow(new RuntimeException("R2 fora")).when(imageStorage).deleteByUrl(anyString());

        String url = service.upload(userId, PNG);

        assertThat(user.getProfileImageUrl()).isEqualTo(url);
    }

    @Test
    @DisplayName("remover tira a URL e apaga o arquivo")
    void remove() {
        user.setProfileImageUrl("https://fotos.example/profile/x.png");

        service.remove(userId);

        assertThat(user.getProfileImageUrl()).isNull();
        verify(imageStorage).deleteByUrl("https://fotos.example/profile/x.png");
    }
}
