package com.salonplatform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.shared.mapping.ResponseMapperConfig;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The code-generation conventions fail closed ({@code docs/engineering/backend.md}, "Code generation"): a
 * Lombok annotation outside the allow-list in {@code lombok.config}, and a MapStruct mapper that leaves a
 * response property unmapped or converts with loss, do not compile. The fixtures under
 * {@code src/test/compile-fixtures} are compiled here, with the build's annotation processors in the
 * build's order and the build's warning flags, and never by the build itself.
 */
class CodeGenerationConventionsTest {

    /** Relative to the module, where Maven runs the tests, so that Lombok finds {@code lombok.config}. */
    private static final Path FIXTURES = Path.of("src", "test", "compile-fixtures", "fixture");

    /** The jars of Lombok, the binding and MapStruct: the order of {@code annotationProcessorPaths}. */
    private static final List<String> PROCESSOR_PATH =
            List.of("lombok.Getter", "lombok.mapstruct.NotifierHider", "org.mapstruct.ap.MappingProcessor");

    /**
     * The processors those jars declare, named so that javac runs exactly these, in this order. In process,
     * javac would otherwise also discover any processor on the test class path.
     */
    private static final String PROCESSORS = String.join(
            ",",
            "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
            "lombok.launch.AnnotationProcessorHider$ClaimingProcessor",
            "org.mapstruct.ap.MappingProcessor");

    private static final List<String> CLASS_PATH = List.of(
            "lombok.Getter",
            "org.mapstruct.Mapper",
            "org.slf4j.Logger",
            "org.springframework.stereotype.Component",
            "org.springframework.beans.factory.annotation.Autowired",
            ResponseMapperConfig.class.getName());

    @TempDir
    Path output;

    @ParameterizedTest(name = "@{0}")
    @ValueSource(
            strings = {
                "Data",
                "Value",
                "EqualsAndHashCode",
                "ToString",
                "Builder",
                "SneakyThrows",
                "AllArgsConstructor",
                "With"
            })
    void aDeniedLombokAnnotationFailsCompilation(String annotation) {
        Compilation compilation = compile("lombok/Denied" + annotation + ".java");

        assertThat(compilation.succeeded()).isFalse();
        assertThat(compilation.errors())
                .anyMatch(error ->
                        error.contains("Use of @" + annotation + " is flagged according to lombok configuration"));
    }

    @Test
    void theAllowedLombokAnnotationsCompileWithoutAWarning() {
        Compilation compilation = compile("lombok/AllowedEntity.java", "lombok/AllowedBean.java");

        assertThat(compilation.diagnostics()).isEmpty();
        assertThat(compilation.succeeded()).isTrue();
    }

    @Test
    void aMapperWithAnUnmappedResponsePropertyFailsCompilation() {
        Compilation compilation = compile("mapstruct/UnmappedTargetMapper.java");

        assertThat(compilation.succeeded()).isFalse();
        assertThat(compilation.errors()).anyMatch(error -> error.contains("Unmapped target property: \"nickname\""));
    }

    @Test
    void theSameMapperWithEveryPropertyMappedCompilesToASpringBean() {
        Compilation compilation = compile("mapstruct/MappedTargetMapper.java");

        assertThat(compilation.diagnostics()).isEmpty();
        assertThat(compilation.succeeded()).isTrue();
        assertThat(output.resolve("classes/fixture/mapstruct/MappedTargetMapperImpl.class"))
                .exists();
        assertThat(output.resolve("generated/fixture/mapstruct/MappedTargetMapperImpl.java"))
                .content()
                .contains("@Component");
    }

    @Test
    void aMapperWithALossyConversionFailsCompilation() {
        Compilation compilation = compile("mapstruct/LossyConversionMapper.java");

        assertThat(compilation.succeeded()).isFalse();
        assertThat(compilation.errors()).anyMatch(error -> error.contains("possibly lossy conversion"));
    }

    private Compilation compile(String... fixtures) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = List.of(
                "-Xlint:all,-processing",
                "-Werror",
                "-processorpath",
                pathOf(PROCESSOR_PATH),
                "-processor",
                PROCESSORS,
                "-classpath",
                pathOf(CLASS_PATH),
                "-d",
                directory("classes"),
                "-s",
                directory("generated"));
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            Iterable<? extends JavaFileObject> sources = files.getJavaFileObjectsFromPaths(
                    Stream.of(fixtures).map(FIXTURES::resolve).toList());
            boolean succeeded = compiler.getTask(null, files, diagnostics, options, null, sources)
                    .call();
            return new Compilation(succeeded, diagnostics.getDiagnostics());
        } catch (IOException exception) {
            throw new IllegalStateException("The compiler's file manager could not be closed", exception);
        }
    }

    private String directory(String name) {
        File directory = output.resolve(name).toFile();
        assertThat(directory.mkdirs()).as("created %s", directory).isTrue();
        return directory.getPath();
    }

    /** The class path entries (jars, or {@code target/classes}) the named classes are loaded from. */
    private static String pathOf(List<String> classNames) {
        return classNames.stream()
                .map(CodeGenerationConventionsTest::locationOf)
                .distinct()
                .reduce((first, second) -> first + File.pathSeparator + second)
                .orElseThrow();
    }

    private static String locationOf(String className) {
        try {
            Class<?> type = Class.forName(className, false, CodeGenerationConventionsTest.class.getClassLoader());
            return Path.of(type.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
        } catch (ClassNotFoundException | URISyntaxException exception) {
            throw new IllegalStateException("Not on the test class path: " + className, exception);
        }
    }

    private record Compilation(boolean succeeded, List<Diagnostic<? extends JavaFileObject>> diagnostics) {

        List<String> errors() {
            return diagnostics.stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                    .toList();
        }
    }
}
