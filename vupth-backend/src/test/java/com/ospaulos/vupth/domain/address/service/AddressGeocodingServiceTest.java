// Mesmo pacote da classe sob teste (convencao do projeto, ver EstablishmentAutoCloseSchedulerTest):
// exercita o corpo do worker, que e package-private de proposito.
package com.ospaulos.vupth.domain.address.service;

import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.address.repository.AddressRepository;
import com.ospaulos.vupth.domain.address.valueObject.LocationStatus;
import com.ospaulos.vupth.infrastructure.geocoding.Coordinates;
import com.ospaulos.vupth.infrastructure.geocoding.GeocodeQuery;
import com.ospaulos.vupth.infrastructure.geocoding.Geocoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests — orquestracao do geocoding de endereco (backlog #2, fatia A). Exercita o corpo do
 * worker direto ({@code resolveAndSave}), sem thread nem transacao real: o que importa aqui e
 * <b>quando</b> o servico externo e chamado, <b>quando</b> a coordenada e gravada e, principalmente,
 * que <b>nada escapa</b> — geocoding e best-effort de ponta a ponta.
 */
@ExtendWith(MockitoExtension.class)
class AddressGeocodingServiceTest {

    @Mock AddressRepository addressRepository;
    @Mock Geocoder geocoder;
    @Mock PlatformTransactionManager transactionManager;

    AddressGeocodingService service;

    private final UUID addressId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // lenient: o teste de backfill nao passa pelo TransactionTemplate, e sob STRICT_STUBS um
        // stub nao usado quebraria a classe inteira.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AddressGeocodingService(addressRepository, geocoder, transactionManager);
    }

    @Test
    @DisplayName("Endereco sem coordenada: geocodifica e GRAVA latitude/longitude")
    void resolvesAndSaves() {
        Address address = addressWithoutCoordinates();
        when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));
        when(geocoder.geocode(any())).thenReturn(Optional.of(new Coordinates(-22.8422, -43.2764)));

        service.resolveAndSave(addressId);

        ArgumentCaptor<Address> saved = ArgumentCaptor.forClass(Address.class);
        verify(addressRepository).save(saved.capture());
        assertThat(saved.getValue().getLatitude()).isEqualTo(-22.8422);
        assertThat(saved.getValue().getLongitude()).isEqualTo(-43.2764);
        assertThat(LocationStatus.of(saved.getValue())).isEqualTo(LocationStatus.LOCATED);
    }

    @Test
    @DisplayName("LocationStatus: sem coordenada e sem falha = PENDING (geocoding na fila)")
    void freshAddressIsPending() {
        assertThat(LocationStatus.of(addressWithoutCoordinates())).isEqualTo(LocationStatus.PENDING);
    }

    @Test
    @DisplayName("A consulta leva o endereco achatado (CEP em 8 digitos + rua/cidade)")
    void buildsQueryFromAddress() {
        when(addressRepository.findById(addressId)).thenReturn(Optional.of(addressWithoutCoordinates()));
        when(geocoder.geocode(any())).thenReturn(Optional.empty());

        service.resolveAndSave(addressId);

        ArgumentCaptor<GeocodeQuery> query = ArgumentCaptor.forClass(GeocodeQuery.class);
        verify(geocoder).geocode(query.capture());
        assertThat(query.getValue().zipCode()).isEqualTo("21012000");
        assertThat(query.getValue().street()).isEqualTo("Avenida Bras de Pina");
        assertThat(query.getValue().city()).isEqualTo("Rio de Janeiro");
        assertThat(query.getValue().hasUsableZipCode()).isTrue();
    }

    @Test
    @DisplayName("Endereco que JA tem coordenada nao chama o geocoder — e o que segura o custo")
    void alreadyGeocodedIsSkipped() {
        Address address = addressWithoutCoordinates();
        address.setLatitude(-22.8422);
        address.setLongitude(-43.2764);
        when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));

        service.resolveAndSave(addressId);

        // Nunca regeocodificar: cada endereco custa UMA chamada externa na vida.
        verifyNoInteractions(geocoder);
        verify(addressRepository, never()).save(any());
    }

    @Test
    @DisplayName("Geocoder nao resolveu: segue sem coordenada e fica marcado UNRESOLVED para o dono ver")
    void unresolvedIsMarkedFailed() {
        Address address = addressWithoutCoordinates();
        when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));
        when(geocoder.geocode(any())).thenReturn(Optional.empty());

        service.resolveAndSave(addressId);

        verify(addressRepository).save(address);
        assertThat(address.getLatitude()).isNull();
        assertThat(LocationStatus.of(address)).isEqualTo(LocationStatus.UNRESOLVED);
    }

    @Test
    @DisplayName("Geocoder EXPLODIU: a falha e engolida (nao pode matar a thread do worker)")
    void geocoderFailureIsSwallowed() {
        Address address = addressWithoutCoordinates();
        when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));
        when(geocoder.geocode(any())).thenThrow(new RuntimeException("BrasilAPI fora do ar"));

        service.resolveAndSave(addressId); // nao lanca

        // Fora do ar conta como nao resolvido: sem retentativa, PENDING ficaria para sempre.
        assertThat(LocationStatus.of(address)).isEqualTo(LocationStatus.UNRESOLVED);
    }

    @Test
    @DisplayName("Endereco inexistente: no-op silencioso")
    void missingAddressIsNoOp() {
        when(addressRepository.findById(addressId)).thenReturn(Optional.empty());

        service.resolveAndSave(addressId);

        verifyNoInteractions(geocoder);
        verify(addressRepository, never()).save(any());
    }

    @Test
    @DisplayName("Backfill enfileira exatamente os enderecos sem coordenada")
    void backfillQueuesAddressesMissingCoordinates() {
        service.init(); // worker (nao ha @PostConstruct fora do container)
        when(addressRepository.findByLatitudeIsNullOrLongitudeIsNull())
                .thenReturn(List.of(addressWithoutCoordinates(), addressWithoutCoordinates()));

        assertThat(service.backfillMissingCoordinates()).isEqualTo(2);
    }

    private Address addressWithoutCoordinates() {
        Address address = new Address();
        address.setId(addressId);
        address.setStreet("Avenida Bras de Pina");
        address.setNumber("100");
        address.setNeighborhood("Penha");
        address.setCity("Rio de Janeiro");
        address.setState("RJ");
        address.setZipCode("21012000");
        return address;
    }
}
