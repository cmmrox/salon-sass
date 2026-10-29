package com.salonplatform.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.StringEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The strings a compiled class carries: its string constants (the literals, and the recipes of string
 * concatenations, in which each joined value is a placeholder character) and the string values of its
 * annotations. Read from the class files rather than the sources, so that the ownership rules see exactly
 * what the compiler kept.
 *
 * @param module the module the class belongs to: the first package segment under the base package
 * @param classAnnotations the annotation types on the class itself
 */
record CompiledStrings(
        String className,
        String module,
        List<String> classAnnotations,
        List<String> constants,
        List<AnnotationString> annotations) {

    /** One string value of an annotation on the class, a field, a method or a parameter. */
    record AnnotationString(String annotation, String element, String value) {}

    /** Every class under a base package in a directory of class files. */
    static List<CompiledStrings> read(Path classes, String basePackage) {
        Path root = classes.resolve(basePackage.replace('.', '/'));
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".class"))
                    .filter(file -> !file.getFileName().toString().equals("package-info.class"))
                    .map(file -> of(read(file), basePackage))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ClassModel read(Path file) {
        try {
            return ClassFile.of().parse(Files.readAllBytes(file));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static CompiledStrings of(ClassModel model, String basePackage) {
        String className = model.thisClass().asInternalName().replace('/', '.');
        String module = className.substring(basePackage.length() + 1).split("\\.")[0];
        List<String> constants = new ArrayList<>();
        model.constantPool().forEach(entry -> {
            if (entry instanceof StringEntry string) {
                constants.add(string.stringValue());
            }
        });
        List<String> classAnnotations = new ArrayList<>();
        List<AnnotationString> annotations = new ArrayList<>();
        model.findAttribute(Attributes.runtimeVisibleAnnotations())
                .ifPresent(attribute -> attribute.annotations().forEach(annotation -> {
                    classAnnotations.add(typeOf(annotation));
                    collect(annotation, annotations);
                }));
        model.fields()
                .forEach(field -> field.findAttribute(Attributes.runtimeVisibleAnnotations())
                        .ifPresent(attribute ->
                                attribute.annotations().forEach(annotation -> collect(annotation, annotations))));
        model.methods().forEach(method -> {
            method.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .ifPresent(attribute ->
                            attribute.annotations().forEach(annotation -> collect(annotation, annotations)));
            method.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                    .ifPresent(attribute -> attribute.parameterAnnotations().stream()
                            .flatMap(List::stream)
                            .forEach(annotation -> collect(annotation, annotations)));
        });
        return new CompiledStrings(
                className, module, List.copyOf(classAnnotations), List.copyOf(constants), List.copyOf(annotations));
    }

    /** Every string the class carries, constants and annotation values together. */
    List<String> all() {
        return Stream.concat(constants.stream(), annotations.stream().map(AnnotationString::value))
                .toList();
    }

    private static void collect(Annotation annotation, List<AnnotationString> into) {
        for (AnnotationElement element : annotation.elements()) {
            collect(typeOf(annotation), element.name().stringValue(), element.value(), into);
        }
    }

    private static String typeOf(Annotation annotation) {
        return annotation.classSymbol().packageName() + "."
                + annotation.classSymbol().displayName();
    }

    private static void collect(String annotation, String element, AnnotationValue value, List<AnnotationString> into) {
        switch (value) {
            case AnnotationValue.OfString string ->
                into.add(new AnnotationString(annotation, element, string.stringValue()));
            case AnnotationValue.OfArray array ->
                array.values().forEach(item -> collect(annotation, element, item, into));
            case AnnotationValue.OfAnnotation nested -> collect(nested.annotation(), into);
            default -> {}
        }
    }
}
