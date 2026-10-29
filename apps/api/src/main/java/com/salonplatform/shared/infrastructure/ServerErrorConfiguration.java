package com.salonplatform.shared.infrastructure;

import org.apache.catalina.Container;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Makes the server answer the requests it rejects itself as problem details, not as its HTML page. */
@Configuration(proxyBeanMethods = false)
class ServerErrorConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> problemErrorReports() {
        return factory -> factory.addContextCustomizers(context -> {
            Container host = context.getParent();
            for (Valve valve : host.getPipeline().getValves()) {
                if (valve.getClass() == ErrorReportValve.class) {
                    host.getPipeline().removeValve(valve);
                }
            }
            // The host adds one valve of this class when it starts.
            if (host instanceof StandardHost standardHost) {
                standardHost.setErrorReportValveClass(ProblemErrorReportValve.class.getName());
            }
        });
    }
}
