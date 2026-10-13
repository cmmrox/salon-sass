package com.salonplatform.shared.application;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Generated source identity must belong to the build checkout, including a linked worktree. */
class BuildSourceMetadataTest {
    @Test
    void generatedCommitMatchesTheBuildCheckoutHead() throws Exception {
        Path api = Path.of("").toAbsolutePath();
        Path root = api.resolve("../..").normalize();
        // Archive/image builds deliberately have no Git directory and retain the unknown fallback.
        assumeTrue(Files.exists(root.resolve(".git")), "Source archive has no Git checkout");
        Process git = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true).start();
        assertThat(git.waitFor(5, TimeUnit.SECONDS)).as("Git revision lookup completes").isTrue();
        String expected = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(git.exitValue()).isZero();
        assertThat(expected).matches("[0-9a-f]{40}");
        Properties generated = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("git.properties")) {
            assertThat(in).as("Build generated Git metadata").isNotNull();
            generated.load(in);
        }
        assertThat(generated.getProperty("git.commit.id"))
                .as("Metadata must identify this checkout, not the linked worktree's common HEAD")
                .isEqualTo(expected);
    }
}
