package com.salonplatform.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.repository.OperatorSecurityStore;
import com.salonplatform.platform.repository.OperatorSecurityStore.RateCount;
import com.salonplatform.platform.repository.OperatorUserStore;
import com.salonplatform.platform.repository.OperatorUserStore.Account;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.shared.web.ApplicationProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * An operator's password is checked (PBKDF2, deliberately slow) holding no platform transaction: no
 * connection of the small platform pool and no lock on the attempt counters. Otherwise a burst of
 * sign-ins queues behind the counter row's lock while each holds a pooled connection, and unrelated
 * operator requests wait past the pool's limit. The attempt is counted, and committed, first.
 */
class OperatorSignInPasswordCheckTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    /** The platform transaction, as far as the service can tell: whether a use case is inside one. */
    static final class RecordingWork extends OperatorWork {
        int depth;

        RecordingWork() {
            super(null);
        }

        @Override
        public <T> T run(Supplier<T> useCase) {
            depth++;
            try {
                return useCase.get();
            } finally {
                depth--;
            }
        }
    }

    private final RecordingWork work = new RecordingWork();
    private final OperatorUserStore users = mock(OperatorUserStore.class);
    private final OperatorSecurityStore security = mock(OperatorSecurityStore.class);
    private final OperatorCrypto crypto = mock(OperatorCrypto.class);
    private final PlatformAudit audit = mock(PlatformAudit.class);
    private final List<Integer> depthAtPasswordCheck = new ArrayList<>();
    private OperatorSignInServiceImpl service;

    @BeforeEach
    void setUp() {
        when(crypto.hash(anyString(), anyString())).thenReturn("hash");
        when(crypto.randomToken()).thenReturn("token");
        when(crypto.matchesPassword(any(), any())).thenAnswer(call -> {
            depthAtPasswordCheck.add(work.depth);
            return "right password".equals(call.getArgument(0));
        });
        service = new OperatorSignInServiceImpl(
                work,
                mock(OperatorSessionTokens.class),
                users,
                security,
                crypto,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void attemptsSoFar(int attempts) {
        when(security.countAttempt(anyString(), anyString(), eq(NOW))).thenReturn(new RateCount(attempts, NOW));
    }

    @Test
    void aWrongPasswordIsCheckedOutsideAnyPlatformTransactionAfterTheAttemptIsCounted() {
        attemptsSoFar(1);
        when(users.findByEmail("someone@example.test"))
                .thenReturn(Optional.of(new Account(
                        UUID.randomUUID(), "someone@example.test", "stored", "OPERATOR_ADMIN", true, null)));

        assertThatThrownBy(() -> service.signIn("Someone@example.test", "wrong", "laptop", "10.0.0.1"))
                .isInstanceOfSatisfying(
                        ApplicationProblemException.class,
                        ex -> assertThat(ex.status()).isEqualTo(401));

        assertThat(depthAtPasswordCheck).containsExactly(0);
        InOrder order = inOrder(security, crypto);
        order.verify(security).countAttempt(eq("operator-email"), anyString(), eq(NOW));
        order.verify(crypto).matchesPassword(any(), any());
    }

    @Test
    void anUnknownEmailIsCheckedAgainstTheDummyHashOutsideAnyPlatformTransactionToo() {
        attemptsSoFar(1);
        when(users.findByEmail("nobody@example.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.signIn("nobody@example.test", "wrong", "laptop", "10.0.0.1"))
                .isInstanceOfSatisfying(
                        ApplicationProblemException.class,
                        ex -> assertThat(ex.status()).isEqualTo(401));

        assertThat(depthAtPasswordCheck).containsExactly(0);
    }

    @Test
    void theRightPasswordIsCheckedOutsideAnyPlatformTransactionAndStillIssuesAChallenge() {
        attemptsSoFar(1);
        UUID id = UUID.randomUUID();
        when(users.findByEmail("admin@example.test"))
                .thenReturn(
                        Optional.of(new Account(id, "admin@example.test", "stored", "OPERATOR_ADMIN", true, "secret")));

        assertThat(service.signIn("admin@example.test", "right password", "laptop", "10.0.0.1")
                        .state())
                .isEqualTo("verify_mfa");

        assertThat(depthAtPasswordCheck).containsExactly(0);
        verify(security).issueChallenge(any(), eq(NOW));
    }

    @Test
    void anAttemptOverTheLimitIsRefusedWithoutCheckingThePassword() {
        attemptsSoFar(OperatorSignInServiceImpl.EMAIL_CEILING + 1);

        assertThatThrownBy(() -> service.signIn("admin@example.test", "right password", "laptop", "10.0.0.1"))
                .isInstanceOfSatisfying(
                        ApplicationProblemException.class,
                        ex -> assertThat(ex.status()).isEqualTo(429));

        verify(crypto, never()).matchesPassword(any(), any());
        verify(users, never()).findByEmail(anyString());
    }
}
