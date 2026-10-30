package com.salonplatform.shared.outbox.impl;

import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.outbox.OutboxProvider;
import com.salonplatform.shared.outbox.OutboxSettings;
import com.salonplatform.shared.tenancy.ActiveTenants;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The outbox's dispatcher, with a guard (a limit on calls at once and a circuit breaker) for each
 * channel that has a provider, and a limit on event deliveries at once. {@code salon.outbox.enabled=false} leaves the dispatcher out; messages
 * are still written, and another instance delivers them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxSettings.class)
class OutboxConfiguration {

    @Bean
    @ConditionalOnProperty(name = "salon.outbox.enabled", havingValue = "true", matchIfMissing = true)
    OutboxDispatcher outboxDispatcher(
            OutboxSettings settings,
            OutboxStore store,
            List<OutboxProvider> providers,
            EventSubscribers events,
            DeclaredEventTypes eventTypes,
            ActiveTenants tenants,
            PlatformTransactionManager transactions,
            Clock clock,
            Traces traces,
            OutboxMetrics metrics) {
        Map<String, ChannelGuard> guards = new HashMap<>();
        for (OutboxProvider provider : providers) {
            guards.put(
                    provider.channel(),
                    new ChannelGuard(
                            settings.provider().concurrency(),
                            settings.breaker().failureThreshold(),
                            settings.breaker().waitDuration(),
                            clock));
        }
        guards.put(
                EventTypes.CHANNEL,
                ChannelGuard.withoutBreaker(settings.provider().concurrency()));
        DeliveryAttempts attempts =
                new DeliveryAttempts(settings, store, providers, events, transactions, clock, traces, metrics);
        return new OutboxDispatcher(
                settings,
                store,
                attempts,
                eventTypes.names(),
                Map.copyOf(guards),
                tenants,
                transactions,
                clock,
                metrics);
    }
}
