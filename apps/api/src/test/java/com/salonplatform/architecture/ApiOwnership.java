package com.salonplatform.architecture;

import com.salonplatform.shared.config.ModuleOwnership;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The path rule of the ownership map (docs/engineering/backend.md, "Ownership of tables and paths"), over an
 * OpenAPI document: every operation carries exactly one tag, its path belongs to a module by the map's
 * longest matching prefix, and the tag is that module's name. Answers the breaches it finds, one sentence
 * each.
 */
final class ApiOwnership {

    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private ApiOwnership() {}

    /** An OpenAPI document, as JSON read into maps and lists. */
    @SuppressWarnings("unchecked") // JSON objects read as maps have String keys.
    static List<String> operationsOutsideTheirModule(ModuleOwnership ownership, Map<String, Object> document) {
        List<String> breaches = new ArrayList<>();
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) document.get("paths");
        paths.forEach((path, item) -> item.forEach((method, value) -> {
            if (!METHODS.contains(method)) {
                return;
            }
            String operation = method.toUpperCase(Locale.ROOT) + " " + path;
            List<String> tags = (List<String>) ((Map<String, Object>) value).getOrDefault("tags", List.of());
            String owner = ownership.ownerOfPath(path).orElse(null);
            if (owner == null) {
                breaches.add(operation + " matches no path prefix in the map");
            } else if (tags.size() != 1) {
                breaches.add(operation + " carries " + tags.size() + " tags " + tags + ", not exactly " + owner);
            } else if (!tags.getFirst().equals(owner)) {
                breaches.add(operation + " is tagged " + tags.getFirst() + " but belongs to " + owner);
            }
        }));
        return breaches;
    }
}
