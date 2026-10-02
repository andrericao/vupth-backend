package com.ospaulos.vupth.infrastructure.storage;

import com.ospaulos.vupth.domain.shared.storage.ImageStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;

/**
 * Cloudflare R2 pela API do S3 (Andre, 25/09 — 10 GB gratis, sem cobranca de trafego). Ligado com
 * {@code R2_ENABLED=true}; sem isso vale o {@link UnconfiguredImageStorage}.
 *
 * <p>O bucket e servido pela URL publica ({@code R2_PUBLIC_BASE_URL}, o dominio r2.dev do bucket ou um
 * dominio proprio): o app le a foto direto dali, sem passar pelo backend.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "vupth.storage.r2.enabled", havingValue = "true")
public class R2ImageStorage implements ImageStorage {

    private final S3Client client;
    private final String bucket;
    private final String publicBaseUrl;

    public R2ImageStorage(
            @Value("${vupth.storage.r2.account-id}") String accountId,
            @Value("${vupth.storage.r2.access-key-id}") String accessKeyId,
            @Value("${vupth.storage.r2.secret-access-key}") String secretAccessKey,
            @Value("${vupth.storage.r2.bucket}") String bucket,
            @Value("${vupth.storage.r2.public-base-url}") String publicBaseUrl) {
        String endpoint = "https://" + normalizeAccountId(accountId) + ".r2.cloudflarestorage.com";
        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        log.info("[Storage] Cloudflare R2 ligado — endpoint={} bucket={} publico={}", endpoint, bucket, this.publicBaseUrl);
    }

    /**
     * Aceita o Account ID puro ou o que a Cloudflare mostra no campo "S3 API" do bucket
     * ({@code https://<id>.r2.cloudflarestorage.com/<bucket>}) — colar a URL inteira foi o primeiro erro de
     * configuracao no staging (25/09): o host virava {@code https://https://...} e o upload dava UnknownHost.
     */
    static String normalizeAccountId(String raw) {
        String id = raw == null ? "" : raw.trim();
        id = id.replaceFirst("^https?://", "");
        int slash = id.indexOf('/');
        if (slash >= 0) {
            id = id.substring(0, slash);
        }
        return id.replaceFirst("\\.r2\\.cloudflarestorage\\.com$", "").replaceFirst("\\.eu$", "");
    }

    @Override
    public String store(String key, byte[] content, String contentType) {
        client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        // a chave muda a cada upload, entao o arquivo nunca muda: cache longo e seguro
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(content));
        return publicBaseUrl + "/" + key;
    }

    @Override
    public void deleteByUrl(String publicUrl) {
        if (publicUrl == null || !publicUrl.startsWith(publicBaseUrl + "/")) {
            return; // nao e deste bucket (URL antiga digitada a mao, por exemplo)
        }
        String key = publicUrl.substring(publicBaseUrl.length() + 1);
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
