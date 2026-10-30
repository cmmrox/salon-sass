package com.salonplatform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.architecture.FixtureCompiler.Compilation;
import com.salonplatform.shared.mapping.ResponseMapperConfig;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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
        List<String> options = List.of(
                "-Xlint:all,-processing",
                "-Werror",
                "-processorpath",
                FixtureCompiler.pathOf(PROCESSOR_PATH),
                "-processor",
                PROCESSORS,
                "-classpath",
                FixtureCompiler.pathOf(CLASS_PATH),
                "-d",
                directory("classes"),
                "-s",
                directory("generated"));
        return FixtureCompiler.compile(
                options,
                Stream.of(fixtures).map(FixtureCompiler.FIXTURES::resolve).toList());
    }

    private String directory(String name) {
        File directory = output.resolve(name).toFile();
        assertThat(directory.mkdirs()).as("created %s", directory).isTrue();
        return directory.getPath();
    }
}
