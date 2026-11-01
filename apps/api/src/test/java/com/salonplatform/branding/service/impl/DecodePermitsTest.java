package com.salonplatform.branding.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Decodes beyond the instance's limit wait briefly and are then refused 503, never queued without bound. */
class DecodePermitsTest {

    @Test
    void aDecodeBeyondTheLimitIsRefusedWithServiceUnavailableAndOneAfterARelease() throws Exception {
        DecodePermits permits = new DecodePermits(1, Duration.ofMillis(100));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> permits.run(() -> {
            holding.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "first";
        }));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatExceptionOfType(ApplicationProblemException.class)
                .isThrownBy(() -> permits.run(() -> "second"))
                .satisfies(refused -> {
                    assertThat(refused.status()).isEqualTo(503);
                    assertThat(refused.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                });

        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
        assertThat(permits.run(() -> "third")).isEqualTo("third");
    }

    @Test
    void aFailingDecodeGivesItsPermitBack() {
        DecodePermits permits = new DecodePermits(1, Duration.ofMillis(50));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> permits.run(() -> {
                    throw new IllegalStateException("decoder failed");
                }));

        assertThat(permits.run(() -> "next")).isEqualTo("next");
    }
}
