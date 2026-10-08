package com.ospaulos.vupth.infrastructure.storage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * awssdk-com-ajuste-do-r2: do SDK 2.30 em diante, o upload leva por padrao um checksum CRC32 (header
 * {@code x-amz-checksum-crc32} ou trailer em {@code aws-chunked}), que o R2 recusava. Um servidor local
 * no lugar do R2 grava o PUT da foto: ele tem que sair sem checksum, como no 2.29.
 */
class R2UploadChecksumTest {

    private HttpServer server;
    private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, List<String>> lower = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach(lower::put);
            headers.set(lower);
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().add("ETag", "\"abc\"");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("upload da foto sai sem checksum CRC32 (header ou trailer) — o R2 aceita")
    void uploadHasNoDefaultChecksum() {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        R2ImageStorage storage = new R2ImageStorage(endpoint, "chave", "segredo", "fotos",
                "https://fotos.exemplo.com/");
        byte[] foto = "conteudo-da-foto".getBytes(StandardCharsets.UTF_8);

        String url = storage.store("perfil/1.jpg", foto, "image/jpeg");

        assertThat(url).isEqualTo("https://fotos.exemplo.com/perfil/1.jpg");
        Map<String, List<String>> sent = headers.get();
        assertThat(sent.keySet()).noneMatch(h -> h.toLowerCase().startsWith("x-amz-checksum-"));
        assertThat(sent).doesNotContainKeys("x-amz-trailer", "x-amz-sdk-checksum-algorithm");
        // aws-chunked aqui e da assinatura em http puro (o teste); no https do R2 o corpo sai sem chunk,
        // como no 2.29 — o que mudou na 2.30 e so o checksum, e e so ele que se confere.
        assertThat(new String(body.get(), StandardCharsets.UTF_8)).contains("conteudo-da-foto");
    }
}
