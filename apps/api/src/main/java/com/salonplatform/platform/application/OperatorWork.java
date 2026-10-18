package com.salonplatform.platform.application;

import java.util.function.Supplier;

import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.infrastructure.PlatformDatabase;

import org.springframework.stereotype.Service;

/**
 * One platform transaction per use case. A refused sign-in, MFA attempt or rate limit (401 or 429)
 * commits its counters, consumption and audit before the error is answered, so a failed HTTP
 * outcome can never undo those controls; every other failure rolls the whole transaction back.
 */
@Service
public class OperatorWork {

    private final PlatformDatabase database;

    public OperatorWork(PlatformDatabase database) {
        this.database = database;
    }

    private record Outcome<T>(T value, ApplicationProblemException failure) {
    }

    public <T> T run(Supplier<T> useCase) {
        Outcome<T> outcome = database.inTransaction(status -> {
            try {
                return new Outcome<>(useCase.get(), null);
            }
            catch (ApplicationProblemException ex) {
                if (ex.status() != 401 && ex.status() != 429) {
                    throw ex;
                }
                return new Outcome<>(null, ex);
            }
        });
        if (outcome.failure() != null) {
            throw outcome.failure();
        }
        return outcome.value();
    }
}
