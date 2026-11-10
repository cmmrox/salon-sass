package com.salonplatform.shared.outbox.impl;

import com.salonplatform.shared.outbox.EventType;
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
 * packages, found once at startup. The dispatcher claims only the types that have a subscriber in this build
 * ({@link EventSubscribers#types}), a subset of these, so that an event of a type this build does not know
 * (decision 064), or knows but does not subscribe to (decision 119), waits unclaimed for an instance that
 * subscribes to it. The declared types are what the dispatcher names at startup as waiting for a subscriber.
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
