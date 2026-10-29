package com.salonplatform.shared.infrastructure;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.util.LambdaSafe;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the server accepts connections (CR-002). With {@code salon.server.listen-on-network} off it
 * listens on this machine's loopback addresses only, IPv4 and IPv6 both, because {@code localhost}
 * and {@code alpha.localhost} resolve to {@code ::1} first on some machines and to
 * {@code 127.0.0.1} first on others. Every other address refuses the connection.
 *
 * <p>Tomcat binds one address per connector, so the IPv6 loopback gets a second connector on the
 * same port, given everything the first one was given: it is built from the same customizers.
 */
@Configuration(proxyBeanMethods = false)
class LoopbackOnlyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LoopbackOnlyConfiguration.class);

    static final String IPV4_LOOPBACK = "127.0.0.1";

    static final String IPV6_LOOPBACK = "::1";

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> loopbackOnly(
            @Value("${salon.server.listen-on-network}") boolean listenOnNetwork) {
        return factory -> {
            if (listenOnNetwork) {
                return;
            }
            if (factory.getAddress() == null) {
                factory.setAddress(address(IPV4_LOOPBACK));
            }
            if (factory.getAddress().isLoopbackAddress() && ipv6LoopbackExists()) {
                factory.addConnectorCustomizers(new IPv6LoopbackSibling(factory));
            }
        };
    }

    private static boolean ipv6LoopbackExists() {
        try {
            return NetworkInterface.getByInetAddress(address(IPV6_LOOPBACK)) != null;
        } catch (SocketException ex) {
            return false;
        }
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Called with the server's main connector once it is fully customized, and adds a second one on
     * the IPv6 loopback, on the same port, customized the same way.
     */
    private static final class IPv6LoopbackSibling implements TomcatConnectorCustomizer {

        private final TomcatServletWebServerFactory factory;

        private boolean added;

        private IPv6LoopbackSibling(TomcatServletWebServerFactory factory) {
            this.factory = factory;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void customize(Connector main) {
            if (added) {
                return;
            }
            added = true;
            if (main.getPort() <= 0) {
                // A port chosen by the system (tests) cannot be shared; the IPv4 loopback serves alone.
                log.debug("No IPv6 loopback connector: the port is chosen at random");
                return;
            }
            Connector sibling = new Connector(factory.getProtocol());
            sibling.setThrowOnFailure(true);
            sibling.setPort(main.getPort());
            if (main.getURIEncoding() != null) {
                sibling.setURIEncoding(main.getURIEncoding());
            }
            ProtocolHandler handler = sibling.getProtocolHandler();
            if (handler instanceof AbstractProtocol<?> protocol) {
                protocol.setAddress(address(IPV6_LOOPBACK));
            }
            // As the factory does for its own connector: a customizer meant for another kind of
            // protocol handler is skipped, not called with the wrong one.
            LambdaSafe.callbacks(
                            TomcatProtocolHandlerCustomizer.class, factory.getProtocolHandlerCustomizers(), handler)
                    .invoke(customizer -> customizer.customize(handler));
            for (TomcatConnectorCustomizer customizer : factory.getConnectorCustomizers()) {
                if (customizer != this) {
                    customizer.customize(sibling);
                }
            }
            factory.addAdditionalConnectors(sibling);
        }
    }
}
