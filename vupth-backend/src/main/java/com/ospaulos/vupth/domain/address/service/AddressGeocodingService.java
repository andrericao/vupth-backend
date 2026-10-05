package com.ospaulos.vupth.domain.address.service;

import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.address.repository.AddressRepository;
import com.ospaulos.vupth.infrastructure.geocoding.Coordinates;
import com.ospaulos.vupth.infrastructure.geocoding.GeocodeQuery;
import com.ospaulos.vupth.infrastructure.geocoding.Geocoder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Preenche {@code latitude}/{@code longitude} de um {@link Address} (backlog #2 — fatia A). E o unico
 * ponto do sistema que fala com um geocoder externo.
 *
 * <p><b>Roda FORA do request.</b> A resolucao acontece numa thread de fundo, disparada <b>apos o
 * commit</b> do cadastro: o usuario nao paga a latencia da chamada externa e — mais importante — uma
 * indisponibilidade do geocoder nao derruba o cadastro do estabelecimento. O endereco nasce sem
 * coordenada e ganha a coordenada segundos depois; ate la ele so nao aparece na busca por raio.</p>
 *
 * <p><b>Uma unica thread</b> (daemon, mesmo motivo dos schedulers do projeto: nao depende de
 * {@code @EnableScheduling} e nunca segura o encerramento da JVM sob {@code reuseForks} no CI). Serializar
 * tambem e o que torna barato respeitar a policy de 1 req/s do Nominatim.</p>
 *
 * <p><b>Custo zero:</b> a coordenada e <b>persistida</b>, entao cada endereco e geocodificado UMA vez
 * — nunca por busca. E isso que mantem o volume dentro do free tier dos servicos gratuitos.</p>
 */
@Slf4j
@Service
public class AddressGeocodingService {

    private final AddressRepository addressRepository;
    private final Geocoder geocoder;
    private final TransactionTemplate transactionTemplate;

    /**
     * Geocodifica no boot os enderecos que ainda estao sem coordenada. Default {@code false}: ligar
     * so quando houver dado legado a corrigir (o backfill respeita o mesmo 1 req/s, entao uma base
     * grande leva minutos) e desligar depois.
     */
    @Value("${vupth.geocoding.backfill-on-boot:false}")
    private boolean backfillOnBoot;

    private ScheduledExecutorService worker;

    public AddressGeocodingService(AddressRepository addressRepository,
                                   Geocoder geocoder,
                                   PlatformTransactionManager transactionManager) {
        this.addressRepository = addressRepository;
        this.geocoder = geocoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void init() {
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "geocoding-1");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    /**
     * Agenda a resolucao do endereco para depois do commit da transacao corrente. Fora de transacao
     * (ex.: backfill), resolve direto. Chamado pelo cadastro/edicao de endereco.
     */
    public void geocodeAfterCommit(UUID addressId) {
        if (addressId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(addressId);
            return;
        }
        // Antes do commit a linha ainda nao existe para a thread de fundo — ela leria "endereco nao
        // encontrado" e desistiria silenciosamente.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(addressId);
            }
        });
    }

    /**
     * Backfill dos enderecos sem coordenada (dado cadastrado antes desta fatia). Enfileira todos na
     * mesma thread, entao saem espacados de 1s — deliberado, e o que respeita o Nominatim.
     *
     * @return quantos enderecos foram enfileirados
     */
    public int backfillMissingCoordinates() {
        List<Address> pending = addressRepository.findByLatitudeIsNullOrLongitudeIsNull();
        log.info("[Geocoding] backfill — {} endereco(s) sem coordenada", pending.size());
        pending.forEach(address -> submit(address.getId()));
        return pending.size();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        if (!backfillOnBoot) {
            return;
        }
        log.info("[Geocoding] boot — backfill ligado (vupth.geocoding.backfill-on-boot=true)");
        backfillMissingCoordinates();
    }

    private void submit(UUID addressId) {
        try {
            worker.submit(() -> resolveAndSave(addressId));
        } catch (RuntimeException e) {
            // Executor ja encerrado (shutdown da app): geocoding e best-effort, nao propaga.
            log.warn("[Geocoding] nao foi possivel enfileirar o endereco {}: {}", addressId, e.toString());
        }
    }

    /**
     * Uma unica nova tentativa depois da pausa que o geocoder pediu (429 — geocoder-429). A segunda
     * falha marca o endereco como nao resolvido: nada de laco contra um servico que esta recusando.
     *
     * @return {@code false} se nao deu para agendar (executor parado) — quem chamou marca a falha
     */
    boolean scheduleRetry(UUID addressId, Duration delay) {
        try {
            worker.schedule(() -> resolveAndSave(addressId, true), delay.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (RuntimeException e) {
            log.warn("[Geocoding] nao foi possivel reagendar o endereco {}: {}", addressId, e.toString());
            return false;
        }
    }

    /**
     * Corpo do worker: le o endereco, consulta o geocoder <b>fora</b> da transacao (chamada de rede
     * nao segura conexao de banco) e grava o resultado numa transacao curta.
     */
    void resolveAndSave(UUID addressId) {
        resolveAndSave(addressId, false);
    }

    void resolveAndSave(UUID addressId, boolean isRetry) {
        try {
            GeocodeQuery query = transactionTemplate.execute(status ->
                    addressRepository.findById(addressId).map(this::toQuery).orElse(null));

            if (query == null) {
                log.info("[Geocoding] endereco {} nao precisa de geocoding (inexistente ou ja com coordenada)",
                        addressId);
                return;
            }

            Optional<Coordinates> resolved = geocoder.geocode(query);
            if (resolved.isEmpty()) {
                Optional<Instant> pausedUntil = geocoder.unavailableUntil();
                if (pausedUntil.isPresent() && !isRetry) {
                    // Folga de 1 s sobre a pausa: chegar no limite exato pegaria o 429 de novo.
                    Duration delay = Duration.between(Instant.now(), pausedUntil.get()).plusSeconds(1);
                    if (delay.isNegative()) {
                        delay = Duration.ofSeconds(1);
                    }
                    if (scheduleRetry(addressId, delay)) {
                        log.info("[Geocoding] endereco {} adiado {} s — geocoder em pausa (429)",
                                addressId, delay.toSeconds());
                        return;
                    }
                }
                log.info("[Geocoding] endereco {} nao pode ser resolvido — segue sem coordenada", addressId);
                markFailed(addressId);
                return;
            }

            Coordinates coordinates = resolved.get();
            transactionTemplate.executeWithoutResult(status ->
                    addressRepository.findById(addressId).ifPresent(address -> {
                        address.setLatitude(coordinates.latitude());
                        address.setLongitude(coordinates.longitude());
                        address.setGeocodingFailed(false);
                        addressRepository.save(address);
                        log.info("[Geocoding] endereco {} geocodificado -> {},{}",
                                addressId, coordinates.latitude(), coordinates.longitude());
                    }));

        } catch (Exception e) {
            // Best-effort de ponta a ponta: nada aqui pode escapar e matar a thread do worker.
            log.warn("[Geocoding] falha ao geocodificar o endereco {}: {}", addressId, e.toString());
            markFailed(addressId);
        }
    }

    /**
     * Deixa o "nao achei" visivel para o dono (LocationStatus.UNRESOLVED). Geocoder fora do ar conta
     * igual: sem retentativa automatica, o endereco ficaria PENDING para sempre.
     */
    private void markFailed(UUID addressId) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    addressRepository.findById(addressId).ifPresent(address -> {
                        address.setGeocodingFailed(true);
                        addressRepository.save(address);
                    }));
        } catch (Exception e) {
            log.warn("[Geocoding] falha ao marcar o endereco {} como nao resolvido: {}", addressId, e.toString());
        }
    }

    /** {@code null} quando o endereco ja tem coordenada — nao regeocodificar e o que segura o custo. */
    private GeocodeQuery toQuery(Address address) {
        if (address.getLatitude() != null && address.getLongitude() != null) {
            return null;
        }
        return new GeocodeQuery(
                address.getZipCode(),
                address.getStreet(),
                address.getNumber(),
                address.getNeighborhood(),
                address.getCity(),
                address.getState());
    }
}
