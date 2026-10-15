package com.ospaulos.vupth.unit.user;

import com.ospaulos.vupth.api.address.dto.request.UpdateAddressRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserProfileGeneralInfosRequest;
import com.ospaulos.vupth.api.user.exception.InvalidBirthDateException;
import com.ospaulos.vupth.api.user.exception.NoFieldsToUpdateException;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.UserAddress;
import com.ospaulos.vupth.domain.user.model.valueObject.Gender;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * BaseUser — regras da entidade sem banco (cobertura-98): edicao do perfil, enderecos (um so
 * principal), papeis e os perfis que cada papel abre.
 */
class BaseUserTest {

    private BaseUser user;

    @BeforeEach
    void setUp() {
        user = new BaseUser();
        user.setId(UUID.randomUUID());
    }

    private UserAddress address(boolean main) {
        UserAddress ua = new UserAddress(new Address());
        ua.setId(UUID.randomUUID());
        ua.setIsMain(main);
        user.addAddress(ua);
        return ua;
    }

    private UpdateAddressRequest update(UUID id, Boolean isMain) {
        return new UpdateAddressRequest(id, isMain, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("updateIfPresent")
    class UpdateProfile {

        @Test
        @DisplayName("tudo nulo ou em branco -> NoFieldsToUpdateException")
        void nothingToUpdate() {
            assertThatThrownBy(() -> user.updateIfPresent(
                    new UserProfileGeneralInfosRequest(" ", "", null, " ", null, null, "")))
                    .isInstanceOf(NoFieldsToUpdateException.class);
            assertThatThrownBy(() -> user.updateIfPresent(
                    new UserProfileGeneralInfosRequest(null, null, null, null, null, null)))
                    .isInstanceOf(NoFieldsToUpdateException.class);
        }

        @Test
        @DisplayName("todos os campos: devolve o que mudou; apelido em branco limpa")
        void allFields() {
            LocalDate birth = LocalDate.now().minusYears(30);

            List<String> modified = user.updateIfPresent(new UserProfileGeneralInfosRequest(
                    "Ana", "Silva", "  ", "11999999999", birth, Gender.values()[0], "https://img"));

            assertThat(modified).containsExactly(
                    "first_name", "last_name", "nickname", "phone", "birth_date", "gender", "profile_image_url");
            assertThat(user.getNickname()).isNull();
            assertThat(user.getAge()).isEqualTo(30);
        }

        @Test
        @DisplayName("nascimento no futuro ou idade impossivel -> InvalidBirthDateException")
        void invalidBirthDate() {
            assertThatThrownBy(() -> user.updateIfPresent(new UserProfileGeneralInfosRequest(
                    null, null, null, LocalDate.now().plusDays(1), null, null)))
                    .isInstanceOf(InvalidBirthDateException.class);
            assertThatThrownBy(() -> user.updateIfPresent(new UserProfileGeneralInfosRequest(
                    null, null, null, LocalDate.now().minusYears(5), null, null)))
                    .isInstanceOf(InvalidBirthDateException.class);
        }
    }

    @Nested
    @DisplayName("enderecos")
    class Addresses {

        @Test
        @DisplayName("o primeiro endereco vira o principal")
        void firstBecomesMain() {
            UserAddress ua = user.addAddress(new Address());

            assertThat(ua.getIsMain()).isTrue();
            assertThat(user.getMainAddress()).isSameAs(ua);
            assertThat(user.getAddress()).containsExactly(ua);
            assertThatThrownBy(() -> user.getAddress().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("dois principais -> erro")
        void twoMains() {
            address(true);
            address(true);

            assertThatThrownBy(user::ensureMainAddress).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("tornar outro principal pela edicao desmarca o antigo")
        void updateToMain() {
            UserAddress first = address(true);
            UserAddress second = address(false);

            user.updateUserAddress(update(second.getId(), true));

            assertThat(first.getIsMain()).isFalse();
            assertThat(second.getIsMain()).isTrue();
        }

        @Test
        @DisplayName("edicao sem mexer no principal")
        void updateWithoutMain() {
            UserAddress first = address(true);

            user.updateUserAddress(update(first.getId(), null));

            assertThat(first.getIsMain()).isTrue();
        }

        @Test
        @DisplayName("endereco inexistente -> EntityNotFoundException")
        void unknownAddress() {
            address(true);

            assertThatThrownBy(() -> user.getUserAddress(UUID.randomUUID()))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> user.removeAddress(UUID.randomUUID()))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("remover deixa o que sobrou como principal")
        void removeKeepsMain() {
            UserAddress first = address(true);
            UserAddress second = address(false);

            user.removeAddress(first.getId());

            assertThat(second.getIsMain()).isTrue();
        }

        @Test
        @DisplayName("setMainAddress troca o principal; repetir no mesmo -> erro")
        void setMain() {
            UserAddress first = address(true);
            UserAddress second = address(false);

            user.setMainAddress(second.getId());

            assertThat(first.getIsMain()).isFalse();
            assertThat(second.getIsMain()).isTrue();
            assertThatThrownBy(() -> user.setMainAddress(second.getId())).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("sem principal -> EntityNotFoundException")
        void noMain() {
            assertThatThrownBy(user::getMainAddress).isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("papeis e perfis")
    class Roles {

        @Test
        @DisplayName("addRole e idempotente; removeRole tira")
        void addAndRemove() {
            user.addRole(RoleType.CLIENT);
            user.addRole(RoleType.CLIENT);

            assertThat(user.getRoles()).hasSize(1);
            assertThat(user.isClient()).isTrue();

            user.removeRole(RoleType.CLIENT);
            assertThat(user.isClient()).isFalse();
        }

        @Test
        @DisplayName("add*Profile abre o perfil e o papel uma vez so")
        void profiles() {
            user.addClientProfile();
            user.addProfessionalProfile();
            user.addOwnerProfile();
            var client = user.getClient();
            user.addClientProfile();
            user.addProfessionalProfile();
            user.addOwnerProfile();

            assertThat(user.hasClientProfile()).isTrue();
            assertThat(user.hasProfessionalProfile()).isTrue();
            assertThat(user.hasOwnerProfile()).isTrue();
            assertThat(user.getClient()).isSameAs(client);
            assertThat(user.getRoles()).hasSize(3);
        }

        @Test
        @DisplayName("get* sem o papel -> IllegalStateException")
        void getterWithoutRole() {
            assertThatThrownBy(user::getProfessional).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(user::getClient).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(user::getOwner).isInstanceOf(IllegalStateException.class);
            assertThat(user.hasClientProfile()).isFalse();
        }

        @Test
        @DisplayName("get* com o papel e sem perfil cria o perfil")
        void getterCreatesProfile() {
            user.addRole(RoleType.PROFESSIONAL);
            user.addRole(RoleType.CLIENT);
            user.addRole(RoleType.OWNER);

            assertThat(user.getProfessional()).isNotNull().isSameAs(user.getProfessional());
            assertThat(user.getClient()).isNotNull().isSameAs(user.getClient());
            assertThat(user.getOwner()).isNotNull().isSameAs(user.getOwner());
        }

        @Test
        @DisplayName("deactivateRole: desativa o papel e mantem o perfil enquanto o papel existe")
        void deactivateRoleKeepsProfile() {
            user.addProfessionalProfile();
            user.addClientProfile();
            user.addOwnerProfile();

            user.deactivateRole(RoleType.PROFESSIONAL);
            user.deactivateRole(RoleType.CLIENT);
            user.deactivateRole(RoleType.OWNER);

            assertThat(user.getRoles()).allMatch(r -> Boolean.FALSE.equals(r.getIsActive()));
            assertThat(user.hasProfessionalProfile()).isTrue();
            assertThat(user.hasClientProfile()).isTrue();
            assertThat(user.hasOwnerProfile()).isTrue();
        }

        @Test
        @DisplayName("deactivateRole de papel que ja nao existe limpa o perfil")
        void deactivateRoleCleansProfile() {
            user.addProfessionalProfile();
            user.addClientProfile();
            user.addOwnerProfile();
            user.removeRole(RoleType.PROFESSIONAL);
            user.removeRole(RoleType.CLIENT);
            user.removeRole(RoleType.OWNER);

            user.deactivateRole(RoleType.PROFESSIONAL);
            user.deactivateRole(RoleType.CLIENT);
            user.deactivateRole(RoleType.OWNER);

            assertThat(user.hasProfessionalProfile()).isFalse();
            assertThat(user.hasClientProfile()).isFalse();
            assertThat(user.hasOwnerProfile()).isFalse();
        }
    }

    @Test
    @DisplayName("idade sem nascimento = 0; activate/deactivate")
    void ageAndActivation() {
        assertThat(user.getAge()).isZero();

        user.deactivate();
        assertThat(user.getIsActive()).isFalse();
        user.activate();
        assertThat(user.getIsActive()).isTrue();
    }
}
