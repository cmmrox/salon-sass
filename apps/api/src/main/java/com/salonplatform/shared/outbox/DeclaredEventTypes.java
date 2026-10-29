package com.salonplatform.shared.outbox;

import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * The event types this build declares: every record annotated with {@link EventType} in the application's
 * packages, found once at startup. The dispatcher claims events of these types only, so that an event a newer
 * version published, of a type this build does not know, waits unclaimed for an instance that knows it
 * (decision 064). A declared type with no subscriber is delivered at once.
 */
@Component
class DeclaredEventTypes {

    private final Set<String> names;

    DeclaredEventTypes(BeanFactory beanFactory) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(EventType.class));
        ClassLoader loader = ClassUtils.getDefaultClassLoader();
        Set<String> found = new TreeSet<>();
        for (String basePackage : AutoConfigurationPackages.get(beanFactory)) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                found.add(EventTypes.nameOf(ClassUtils.resolveClassName(candidate.getBeanClassName(), loader)));
            }
        }
        this.names = Set.copyOf(found);
    }

    /** The type names, such as {@code identity.CustomerDataErased.v1}. */
    Set<String> names() {
        return names;
    }
}
