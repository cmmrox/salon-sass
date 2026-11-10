package com.salonplatform.identity.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Decision 120, BUG-F02-016: password checks run outside any transaction, and only so many at once. */
class PasswordChecksTest {

    private final IdentityCrypto crypto = mock(IdentityCrypto.class);
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch checking = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        threads.shutdownNow();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void aCheckBeyondTheLimitWaitsBrieflyAndIsThenRefusedAsUnavailable() throws Exception {
        when(crypto.matchesPassword(any(), any())).thenAnswer(call -> {
            checking.countDown();
            return release.await(10, TimeUnit.SECONDS);
        });
        PasswordChecks checks = new PasswordChecks(crypto, 1);
        Future<Boolean> first = threads.submit(() -> checks.matches("password", "hash"));
        assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThatThrownBy(() -> checks.matches("password", "hash"))
                .isInstanceOfSatisfying(ApplicationProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(503);
                    assertThat(ex.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                });
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(PasswordChecks.WAIT.toMillis() - 50);

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        when(crypto.matchesPassword(any(), any())).thenReturn(false);
        assertThat(checks.matches("password", "hash")).isFalse();
    }

    @Test
    void aCheckInsideATransactionIsRefusedBeforeHashing() {
        PasswordChecks checks = new PasswordChecks(crypto, 2);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> checks.matches("password", "hash")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> checks.encode("password")).isInstanceOf(IllegalStateException.class);
        verify(crypto, never()).matchesPassword(any(), any());
        verify(crypto, never()).encodePassword(any());
    }

    @Test
    void aLimitBelowOneIsRefusedAtStartup() {
        assertThatThrownBy(() -> new PasswordChecks(crypto, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
