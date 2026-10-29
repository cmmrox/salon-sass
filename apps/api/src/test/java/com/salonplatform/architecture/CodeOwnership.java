package com.salonplatform.architecture;

import com.salonplatform.architecture.CompiledStrings.AnnotationString;
import com.salonplatform.shared.infrastructure.ModuleOwnership;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The code rules of the ownership map (docs/engineering/backend.md, "Ownership of tables and paths"), over
 * the strings of compiled classes ({@link CompiledStrings}):
 *
 * <ul>
 *   <li>a module names only its own tables: in {@code @Table}, and in the SQL and JPQL its strings hold, where
 *       a table or an entity follows {@code FROM}, {@code JOIN}, {@code INTO}, {@code UPDATE}, {@code TABLE} or
 *       {@code TRUNCATE}. An entity in JPQL is its class's module's; {@code shared}'s tables are therefore
 *       named only from {@code shared};
 *   <li>a module reads only its own settings and {@code shared}'s, which every module runs on: a string
 *       starting {@code salon.}, or a {@code ${salon.…}} placeholder, under another business module's setting
 *       prefix is a read of that module's settings. {@code shared} reads only its own.
 * </ul>
 */
final class CodeOwnership {

    private static final String SHARED = "shared";

    private static final Set<String> TABLE_ANNOTATIONS = Set.of(
            "jakarta.persistence.Table",
            "jakarta.persistence.SecondaryTable",
            "jakarta.persistence.JoinTable",
            "jakarta.persistence.CollectionTable");

    private static final String ENTITY = "jakarta.persistence.Entity";

    private static final Pattern NAMED_TABLE = Pattern.compile(
            "\\b(?:FROM|JOIN|INTO|UPDATE|TABLE|TRUNCATE)\\s+(?:ONLY\\s+)?(?:public\\.)?([A-Za-z_][A-Za-z0-9_]*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SETTING = Pattern.compile("^(salon\\.[A-Za-z0-9.-]+)|\\$\\{(salon\\.[A-Za-z0-9.-]+)");

    /** One class naming one table or entity, and the module that owns what it names. */
    record TableReference(String className, String module, String named, String owner) {}

    private CodeOwnership() {}

    /** Every table or entity the classes name that the map or the classes say who owns. */
    static List<TableReference> tableReferences(ModuleOwnership ownership, List<CompiledStrings> classes) {
        Map<String, String> entityModules = entityModules(classes);
        List<TableReference> references = new ArrayList<>();
        for (CompiledStrings type : classes) {
            for (AnnotationString annotation : type.annotations()) {
                if (TABLE_ANNOTATIONS.contains(annotation.annotation())
                        && annotation.element().equals("name")) {
                    ownership
                            .ownerOfTable(annotation.value())
                            .ifPresent(owner -> references.add(
                                    new TableReference(type.className(), type.module(), annotation.value(), owner)));
                }
            }
            for (String text : type.all()) {
                Matcher named = NAMED_TABLE.matcher(text);
                while (named.find()) {
                    String name = named.group(1);
                    ownership
                            .ownerOfTable(name)
                            .or(() -> Optional.ofNullable(entityModules.get(name)))
                            .ifPresent(owner ->
                                    references.add(new TableReference(type.className(), type.module(), name, owner)));
                }
            }
        }
        return references;
    }

    /** The tables and entities a module names that another module owns. */
    static List<String> foreignTables(ModuleOwnership ownership, List<CompiledStrings> classes) {
        return tableReferences(ownership, classes).stream()
                .filter(reference -> !reference.owner().equals(reference.module()))
                .map(reference -> reference.className() + " (" + reference.module() + ") names " + reference.named()
                        + ", which is " + reference.owner() + "'s")
                .distinct()
                .toList();
    }

    /** The settings a module reads that belong to another business module, or that shared reads of one. */
    static List<String> foreignSettings(ModuleOwnership ownership, List<CompiledStrings> classes) {
        List<String> breaches = new ArrayList<>();
        for (CompiledStrings type : classes) {
            for (String text : type.all()) {
                Matcher setting = SETTING.matcher(text);
                while (setting.find()) {
                    String key = setting.group(1) != null ? setting.group(1) : setting.group(2);
                    ownership
                            .ownerOfSetting(key)
                            .filter(owner -> !owner.equals(type.module()) && !owner.equals(SHARED))
                            .ifPresent(owner -> breaches.add(type.className() + " (" + type.module() + ") reads " + key
                                    + ", which is " + owner + "'s"));
                }
            }
        }
        return breaches.stream().distinct().toList();
    }

    /** Each entity's name, as JPQL names it, and the module its class is in. */
    private static Map<String, String> entityModules(List<CompiledStrings> classes) {
        return classes.stream()
                .filter(type -> type.classAnnotations().contains(ENTITY))
                .collect(Collectors.toMap(CodeOwnership::entityName, CompiledStrings::module, (first, _) -> first));
    }

    private static String entityName(CompiledStrings type) {
        return type.annotations().stream()
                .filter(annotation -> annotation.annotation().equals(ENTITY)
                        && annotation.element().equals("name"))
                .map(AnnotationString::value)
                .findFirst()
                .orElse(type.className().substring(type.className().lastIndexOf('.') + 1));
    }
}
