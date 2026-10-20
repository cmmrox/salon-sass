package com.salonplatform.platform.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The lifecycle terms the owner set (decision 049; docs/architecture/operator-console.md, "Suspension,
 * closure, export and retention"): a reason of 10 to 500 characters for every lifecycle action, and a
 * closed salon's tenant data kept for exactly 30 days of 24 hours after its closure instant, during
 * which its export can be downloaded.
 */
public final class LifecycleTerms {

    public static final int REASON_MIN = 10;
    public static final int REASON_MAX = 500;

    /** How long a closed salon's data and export are kept. */
    public static final Duration RETENTION = Duration.ofDays(30);

    private LifecycleTerms() {
    }

    /** The trimmed reason when it is 10 to 500 characters of plain text (line breaks allowed); else null. */
    public static String reason(String value) {
        String text = PlainText.block(value, REASON_MAX);
        return text == null || text.codePointCount(0, text.length()) < REASON_MIN ? null : text;
    }

    /** When a salon closed at this instant has its data deleted. */
    public static Instant deleteAt(Instant closedAt) {
        return closedAt.plus(RETENTION);
    }
}
