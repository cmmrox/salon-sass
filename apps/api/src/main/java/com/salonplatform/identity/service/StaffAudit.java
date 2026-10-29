package com.salonplatform.identity.service;

import com.salonplatform.identity.dto.AuditView;
import com.salonplatform.identity.dto.Page;
import com.salonplatform.shared.audit.AuditTrail;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The salon's identity and support-session audit entries, as the Owner and Manager read them: only nonsecret
 * values, allowlisted per action (docs/architecture/identity-and-access.md).
 */
@Service
@RequiredArgsConstructor
public class StaffAudit {

    private static final List<String> ACCESS_VALUES = List.of("role", "professionalGrant", "active");

    private static final List<String> SUPPORT_VALUES =
            List.of("operatorName", "reason", "expiresAt", "method", "route", "status", "endedBy");

    private static final List<String> PROFILE_FIELDS =
            List.of("name", "email", "birthday", "preferredLanguage", "notificationPreferences", "marketingConsent");

    private final IdentityWork work;
    private final StaffAuthority authority;
    private final AuditTrail audit;

    public Page<AuditView> list(int page, int size, String subjectId) {
        Pagination.requireValid(page, size);
        return work.run(() -> {
            authority.requireReadable("audit.list");
            AuditTrail.AuditPage rows = audit.identityPage(page, size, subjectId);
            return new Page<>(
                    rows.items().stream()
                            .map(entry -> new AuditView(
                                    entry.id(),
                                    entry.occurredAt(),
                                    entry.actorKind(),
                                    entry.actorId(),
                                    entry.action(),
                                    entry.subjectKind(),
                                    entry.subjectId(),
                                    safeValues(entry.action(), entry.before()),
                                    safeValues(entry.action(), entry.after())))
                            .toList(),
                    page,
                    size,
                    rows.total());
        });
    }

    private static Map<String, Object> safeValues(String action, Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        for (String key : ACCESS_VALUES) {
            if (input.containsKey(key)) {
                result.put(key, input.get(key));
            }
        }
        if (action != null && action.startsWith("support.")) {
            for (String key : SUPPORT_VALUES) {
                if (input.get(key) instanceof String || input.get(key) instanceof Number) {
                    result.put(key, input.get(key));
                }
            }
        }
        if ("identity.profile_updated".equals(action)) {
            Object version = input.get("version");
            if ((version instanceof Integer || version instanceof Long) && ((Number) version).longValue() >= 0) {
                result.put("version", version);
            }
            if (input.get("changedFields") instanceof List<?> fields) {
                result.put(
                        "changedFields",
                        fields.stream()
                                .filter(field -> field instanceof String && PROFILE_FIELDS.contains(field))
                                .distinct()
                                .toList());
            }
        }
        return Map.copyOf(result);
    }
}
