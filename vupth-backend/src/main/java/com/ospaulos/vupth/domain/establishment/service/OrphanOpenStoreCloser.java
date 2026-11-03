package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * loja-so-abre-pelo-app (BAK-8, DEC-159): a loja so consta aberta se o dono ou o primeiro profissional a abriu
 * no app, e fecha quando o ultimo sai. Estado velho (OPEN gravado sem ninguem em WORKING/ON_BREAK — cadastro
 * antigo que aceitava open_status, dado de teste, saida que nao recalculou) fecha na subida do servico. Nunca
 * abre nada: so fecha. Falha aqui nao impede o boot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanOpenStoreCloser implements ApplicationRunner {

    private final EstablishmentRepository establishments;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int closed = closeOrphans();
            if (closed > 0) {
                log.warn("[OpenStatus] {} loja(s) estavam OPEN sem ninguem trabalhando — fechadas", closed);
            }
        } catch (RuntimeException e) {
            log.error("[OpenStatus] varredura de lojas abertas sem ninguem falhou: {}", e.toString());
        }
    }

    /** Fecha as lojas OPEN sem profissional em expediente; devolve quantas. */
    @Transactional
    public int closeOrphans() {
        List<Establishment> orphans = establishments.findOpenWithNobodyWorking();
        for (Establishment e : orphans) {
            e.setOpenStatus(OpenStatus.CLOSED);
            e.setLastStatusChange(ZonedDateTime.now());
            log.info("[OpenStatus] loja {} fechada: OPEN sem ninguem trabalhando", e.getId());
        }
        establishments.saveAll(orphans);
        return orphans.size();
    }
}
