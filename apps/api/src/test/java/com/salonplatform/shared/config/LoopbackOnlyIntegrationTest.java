package com.salonplatform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * CR-002: told to stay off the network, the API is bound to this machine's loopback addresses,
 * IPv4 and IPv6 both, and to nothing else; told to listen on the network, to every interface.
 *
 * <p>What the server is bound to is read from the server, which holds on any machine. Whether a
 * connection to the machine's network address is then refused or accepted is checked as well, but
 * only where a listener on every interface can be reached on that address at all: where it cannot
 * (a sandbox, a network policy), a refused connection would prove nothing.
 */
class LoopbackOnlyIntegrationTest {

    private static final String META =
            "GET /api/v1/meta HTTP/1.1\r\nHost: alpha.localhost\r\nConnection: close\r\n\r\n";

    @Test
    void offTheNetworkItIsBoundToTheLoopbackAddressesOnlyAndAnswersOnBoth() throws IOException {
        int port = freePort();
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication application =
                database.start("--server.port=" + port, "--salon.server.listen-on-network=false")) {
            Http http = new Http(port);

            if (hasIPv6Loopback()) {
                assertThat(application.listenAddresses()).containsExactlyInAnyOrder("127.0.0.1", "0:0:0:0:0:0:0:1");
                assertThat(http.raw("::1", META)).as("on ::1").startsWith("HTTP/1.1 200");
            } else {
                assertThat(application.listenAddresses()).containsExactly("127.0.0.1");
            }
            assertThat(http.raw("127.0.0.1", META)).as("on 127.0.0.1").startsWith("HTTP/1.1 200");
        }
    }

    @Test
    void offTheNetworkAConnectionToTheNetworkAddressIsRefused() throws IOException {
        InetAddress network = reachableNetworkAddress();
        int port = freePort();
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication _ = database.start("--server.port=" + port, "--salon.server.listen-on-network=false")) {
            assertThatExceptionOfType(ConnectException.class)
                    .as("a connection to %s:%d", network.getHostAddress(), port)
                    .isThrownBy(() -> connect(network, port));
        }
    }

    /** The second connector is the first one's equal: its requests run on virtual threads too. */
    @Test
    void theIPv6LoopbackServesRequestsTheWayTheIPv4LoopbackDoes() throws IOException {
        assumeTrue(hasIPv6Loopback(), "this machine has no IPv6 loopback");
        int port = freePort();
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication _ = database.start("--server.port=" + port, "--spring.profiles.active=local")) {
            String info = "GET /actuator/info HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            for (String address : new String[] {"127.0.0.1", "::1"}) {
                Exchange response = Exchange.parse(new Http(port).raw(address, info));

                assertThat(response.status()).as(address).isEqualTo(200);
                assertThat(JsonPath.parse(response.body()).<Boolean>read("$.runtime.virtualThreads"))
                        .as(address)
                        .isTrue();
            }
            String malformed = new Http(port)
                    .raw("::1", "GET /api/v1/%zz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertThat(malformed).startsWith("HTTP/1.1 400").containsIgnoringCase("application/problem+json");
        }
    }

    @Test
    void onTheNetworkItIsBoundToEveryInterface() throws IOException {
        int port = freePort();
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication application =
                database.start("--server.port=" + port, "--salon.server.listen-on-network=true")) {
            assertThat(application.listenAddresses()).containsExactly("*");
            assertThat(new Http(port).raw("127.0.0.1", META)).startsWith("HTTP/1.1 200");
        }
    }

    @Test
    void onTheNetworkItAnswersOnTheNetworkAddress() throws IOException {
        InetAddress network = reachableNetworkAddress();
        int port = freePort();
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication _ = database.start("--server.port=" + port, "--salon.server.listen-on-network=true")) {
            assertThat(new Http(port).raw(network.getHostAddress(), META))
                    .as("on %s", network.getHostAddress())
                    .startsWith("HTTP/1.1 200");
        }
    }

    /**
     * One of this machine's network addresses, IPv4 or IPv6, on which a listener of the test's own
     * on every interface has just been reached. Skips the test where there is none: a machine with
     * no network, or one where a route or a sandbox keeps it from reaching its own address.
     */
    private static InetAddress reachableNetworkAddress() throws IOException {
        List<InetAddress> candidates = networkAddresses();
        assumeTrue(!candidates.isEmpty(), "this machine has no network address");
        try (ServerSocket everyInterface = new ServerSocket(0)) {
            for (InetAddress candidate : candidates) {
                try {
                    connect(candidate, everyInterface.getLocalPort());
                    return candidate;
                } catch (IOException ex) {
                    // Not reachable from here; a refused connection on it would prove nothing.
                }
            }
        }
        assumeTrue(
                false,
                "a listener on every interface cannot be reached on any of " + candidates
                        + " from here, so a refused connection would prove nothing");
        throw new IllegalStateException("unreachable");
    }

    private static void connect(InetAddress address, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 3_000);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean hasIPv6Loopback() throws IOException {
        return NetworkInterface.getByInetAddress(InetAddress.getByName("::1")) != null;
    }

    /** The addresses of the interfaces that are up, leaving out loopback and link-local ones. */
    private static List<InetAddress> networkAddresses() throws SocketException {
        List<InetAddress> addresses = new ArrayList<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    addresses.add(address);
                }
            }
        }
        return addresses;
    }
}
