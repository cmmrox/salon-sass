package com.salonplatform.architecture;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles the fixtures under {@code src/test/compile-fixtures}, which the build never compiles itself: sources
 * that must fail to compile, and classes that must stay off the test class path, where Spring would scan them.
 */
final class FixtureCompiler {

    /** Relative to the module, where Maven runs the tests, so that Lombok finds {@code lombok.config}. */
    static final Path FIXTURES = Path.of("src", "test", "compile-fixtures", "fixture");

    private FixtureCompiler() {}

    static Compilation compile(List<String> options, List<Path> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sources);
            boolean succeeded = compiler.getTask(null, files, diagnostics, options, null, units)
                    .call();
            return new Compilation(succeeded, diagnostics.getDiagnostics());
        } catch (IOException exception) {
            throw new IllegalStateException("The compiler's file manager could not be closed", exception);
        }
    }

    /** The class path entries (jars, or {@code target/classes}) the named classes are loaded from. */
    static String pathOf(List<String> classNames) {
        return classNames.stream()
                .map(FixtureCompiler::locationOf)
                .distinct()
                .reduce((first, second) -> first + File.pathSeparator + second)
                .orElseThrow();
    }

    private static String locationOf(String className) {
        try {
            Class<?> type = Class.forName(className, false, FixtureCompiler.class.getClassLoader());
            return Path.of(type.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
        } catch (ClassNotFoundException | URISyntaxException exception) {
            throw new IllegalStateException("Not on the test class path: " + className, exception);
        }
    }

    record Compilation(boolean succeeded, List<Diagnostic<? extends JavaFileObject>> diagnostics) {

        List<String> errors() {
            return diagnostics.stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                    .toList();
        }
    }
}
