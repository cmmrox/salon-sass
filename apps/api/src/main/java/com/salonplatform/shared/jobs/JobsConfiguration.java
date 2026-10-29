package com.salonplatform.shared.jobs;

import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.tenancy.ActiveTenants;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** The scheduler of the recurring jobs. {@code salon.jobs.enabled=false} leaves it out. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobSettings.class)
class JobsConfiguration {

    @Bean
    @ConditionalOnProperty(name = "salon.jobs.enabled", havingValue = "true", matchIfMissing = true)
    JobScheduler jobScheduler(
            JobSettings settings,
            JobStore store,
            List<TenantJob> jobs,
            ObjectProvider<PlatformJob> platformJobs,
            ActiveTenants tenants,
            PlatformTransactionManager transactions,
            Clock clock,
            Traces traces,
            MeterRegistry meters,
            TenantJobActivity activity) {
        return new JobScheduler(
                settings,
                store,
                jobs,
                platformJobs.orderedStream().toList(),
                tenants,
                transactions,
                clock,
                traces,
                meters,
                activity);
    }
}
