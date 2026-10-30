package com.salonplatform.identity.adapter;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Private BFF rate-address proof; it never provides tenant or authentication authority. */
@Component
public final class ClientAddressAssertion {
    public static final String HEADER = "x-salon-client-assertion";
    public static final int MAX_LENGTH = 192;
    private static final Set<String> MUTATIONS =
            Set.of("/api/v1/identity/staff/sign-in", "/api/v1/identity/customers/challenges");
    private static final Pattern INSTANT =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?Z");
    private final byte[] key;
    private final Clock clock;
    private final String platformDomain;

    public ClientAddressAssertion(
            IdentitySecrets secrets, Clock clock, @Value("${salon.platform-domain}") String platformDomain) {
        this.key = secrets.proxy();
        this.clock = clock;
        this.platformDomain = platformDomain.trim().toLowerCase(Locale.ROOT);
    }

    public String rateAddress(HttpServletRequest request) {
        var headers = request.getHeaders(HEADER);
        if (headers == null || !headers.hasMoreElements()) return request.getRemoteAddr();
        String proof = headers.nextElement();
        if (headers.hasMoreElements() || proof == null || proof.length() > MAX_LENGTH) throw invalid();
        String path = com.salonplatform.shared.web.RequestPaths.path(request);
        if (!"POST".equals(request.getMethod()) || path == null || !MUTATIONS.contains(path)) throw invalid();
        return verify(proof, TenantContext.require().slug() + "." + platformDomain, request.getMethod(), path);
    }

    String verify(String proof, String host, String method, String path) {
        try {
            if (proof == null || proof.length() > MAX_LENGTH || !proof.chars().allMatch(c -> c >= 33 && c <= 126))
                throw invalid();
            String[] fields = proof.split(";", -1);
            if (fields.length != 4
                    || !fields[0].equals("v1")
                    || !fields[1].equals(canonicalIp(fields[1]))
                    || !INSTANT.matcher(fields[2]).matches()
                    || !fields[3].matches("[0-9a-f]{64}")) throw invalid();
            Instant issued = Instant.parse(fields[2]);
            if (Duration.between(issued, clock.instant()).abs().compareTo(Duration.ofSeconds(30)) > 0) throw invalid();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String canonical = "v1\n" + host + "\n" + method + "\n" + path + "\n" + fields[1] + "\n" + fields[2];
            if (!MessageDigest.isEqual(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)),
                    HexFormat.of().parseHex(fields[3]))) throw invalid();
            return fields[1];
        } catch (ApplicationProblemException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    /** Literal-only parsing: no DNS, lists, ports, brackets, zones or legacy IPv4 notation. */
    public static String canonicalIp(String input) {
        if (input == null || input.length() > 45 || !input.matches("[0-9A-Fa-f:.]+")) throw invalid();
        if (!input.contains(":")) return ipv4(input);
        int dot = input.indexOf('.');
        if (dot >= 0) ipv4(input.substring(input.lastIndexOf(':') + 1));
        byte[] bytes = InetAddress.ofLiteral(input).getAddress();
        if (bytes.length == 4) return dotted(bytes, 0);
        boolean mapped = true;
        for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
        if (mapped && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) return dotted(bytes, 12);
        StringJoiner groups = new StringJoiner(":");
        for (int i = 0; i < 16; i += 2)
            groups.add(String.format(Locale.ROOT, "%04x", ((bytes[i] & 255) << 8) | (bytes[i + 1] & 255)));
        return groups.toString();
    }

    private static String ipv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) throw invalid();
        for (String part : parts)
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) throw invalid();
        return String.join(".", parts);
    }

    private static String dotted(byte[] bytes, int start) {
        return (bytes[start] & 255)
                + "."
                + (bytes[start + 1] & 255)
                + "."
                + (bytes[start + 2] & 255)
                + "."
                + (bytes[start + 3] & 255);
    }

    private static ApplicationProblemException invalid() {
        return new ApplicationProblemException(400, ErrorCode.INVALID_CLIENT_ASSERTION);
    }
}
