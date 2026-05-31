package com.ospaulos.vupth.infrastructure.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class GitCommitInfoContributor implements InfoContributor {

    @Value("${RENDER_GIT_COMMIT:unknown}")
    private String renderGitCommit;

    @Override
    public void contribute(Info.Builder builder) {
        String shortCommit = renderGitCommit.length() >= 7
                ? renderGitCommit.substring(0, 7)
                : renderGitCommit;

        builder.withDetail("git", Map.of(
                "commit", Map.of("id", shortCommit)
        ));
    }
}
