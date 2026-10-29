package com.salonplatform.shared.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The ownership map, {@value #RESOURCE}: the tables, API path prefixes and setting prefixes each module owns
 * (docs/engineering/backend.md, "Ownership of tables and paths"). A path or a setting belongs to the module
 * with the longest prefix that matches it whole segment by whole segment (decision 058), so
 * {@code /api/v1/salon} matches {@code /api/v1/salon/invoices} but not {@code /api/v1/salons}. A table, a
 * path prefix or a setting prefix listed twice is refused, because it would have two owners.
 */
public final class ModuleOwnership {

    /** Where the map is on the class path. */
    public static final String RESOURCE = "module-ownership.yaml";

    /** One module's entry in the map. */
    public record Module(
            String name, String description, List<String> tables, List<String> paths, List<String> settings) {}

    private final List<Module> modules;

    private ModuleOwnership(List<Module> modules) {
        this.modules = List.copyOf(modules);
        refuseTwoOwners("table", Module::tables);
        refuseTwoOwners("path", Module::paths);
        refuseTwoOwners("setting", Module::settings);
    }

    /** The application's map. */
    public static ModuleOwnership load() {
        try (InputStream yaml = ModuleOwnership.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (yaml == null) {
                throw new IllegalStateException(RESOURCE + " is not on the class path");
            }
            return read(yaml);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** A map in the format of {@value #RESOURCE}. */
    public static ModuleOwnership read(InputStream yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<String, Object> document = mapOf(new Yaml(new SafeConstructor(options)).load(yaml), RESOURCE);
        List<Module> modules = new ArrayList<>();
        mapOf(document.get("modules"), "modules").forEach((name, entry) -> {
            Map<String, Object> module = mapOf(entry, name);
            modules.add(new Module(
                    name,
                    String.valueOf(module.getOrDefault("description", "")),
                    listOf(module.get("tables")),
                    listOf(module.get("paths")),
                    listOf(module.get("settings"))));
        });
        return new ModuleOwnership(modules);
    }

    public List<Module> modules() {
        return modules;
    }

    /** A module's entry; the module must be in the map. */
    public Module module(String name) {
        return modules.stream()
                .filter(module -> module.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(RESOURCE + " has no module " + name));
    }

    /** The module that owns a table, when the map lists it. */
    public Optional<String> ownerOfTable(String table) {
        return modules.stream()
                .filter(module -> module.tables().contains(table))
                .map(Module::name)
                .findFirst();
    }

    /** The module that owns a request path, by the longest matching prefix. */
    public Optional<String> ownerOfPath(String path) {
        return longestMatch(path, Module::paths, '/');
    }

    /** The module that owns a setting, by the longest matching prefix. */
    public Optional<String> ownerOfSetting(String setting) {
        return longestMatch(setting, Module::settings, '.');
    }

    /** Whether {@code value} is {@code prefix} itself, or continues it after a separator. */
    public static boolean underPrefix(String value, String prefix, char separator) {
        return value.equals(prefix) || value.startsWith(prefix + separator);
    }

    private Optional<String> longestMatch(String value, Function<Module, List<String>> prefixes, char separator) {
        record Claim(String module, String prefix) {}
        return modules.stream()
                .flatMap(module -> prefixes.apply(module).stream().map(prefix -> new Claim(module.name(), prefix)))
                .filter(claim -> underPrefix(value, claim.prefix(), separator))
                .max(Comparator.comparingInt(claim -> claim.prefix().length()))
                .map(Claim::module);
    }

    private void refuseTwoOwners(String kind, Function<Module, List<String>> entries) {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        modules.forEach(module -> entries.apply(module)
                .forEach(entry ->
                        owners.computeIfAbsent(entry, _ -> new ArrayList<>()).add(module.name())));
        String listedTwice = owners.entrySet().stream()
                .filter(owner -> owner.getValue().size() > 1)
                .map(owner -> owner.getKey() + " (" + String.join(", ", owner.getValue()) + ")")
                .collect(Collectors.joining(", "));
        if (!listedTwice.isEmpty()) {
            throw new IllegalStateException("Each " + kind + " has one owning module, but " + RESOURCE
                    + " lists more than once: " + listedTwice);
        }
    }

    // The safe constructor gives a YAML mapping as a Map with String keys; the instanceof test is the check.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value, String name) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException(RESOURCE + ": '" + name + "' must be a mapping");
        }
        return (Map<String, Object>) value;
    }

    private static List<String> listOf(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(RESOURCE + ": tables, paths and settings must be lists");
        }
        return list.stream().map(String::valueOf).toList();
    }
}
