package com.salonplatform.platform.domain;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.List;
import java.util.UUID;

/**
 * The {@code Idempotency-Key} of an idempotent creation (docs/architecture/operator-console.md, "Profile, plans
 * and retry rules"): a UUID, scoped to the operator and the action by the receipt it keys.
 */
public final class IdempotencyKey {

    public static final String HEADER = "Idempotency-Key";

    private IdempotencyKey() {}

    /**
     * The key from its header: a UUID, or 400 {@code bad_request} naming the header.
     */
    public static UUID parse(String header) {
        if (header != null
                && header.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return UUID.fromString(header);
        }
        throw new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem(HEADER, "must be a UUID")));
    }
}
