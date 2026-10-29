package com.salonplatform.platform.config;

import com.salonplatform.platform.service.OperatorAccounts;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The explicit offline operator bootstrap (docs/architecture/operator-console.md). Reads
 * {@code email}, {@code displayName}, {@code role} and {@code password} from a properties file that
 * only its owner may read or write, creates that operator unless the email already has an account,
 * and reports which happened without printing any value. Ordinary starts never create an operator.
 */
@Component
@Profile("operator-bootstrap")
class OperatorBootstrapCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OperatorBootstrapCommand.class);

    private final OperatorAccounts accounts;
    private final String file;

    OperatorBootstrapCommand(OperatorAccounts accounts, @Value("${salon.operator.bootstrap-file:}") String file) {
        this.accounts = accounts;
        this.file = file;
    }

    @Override
    public void run(ApplicationArguments arguments) throws IOException {
        if (file == null || file.isBlank()) {
            throw new IllegalStateException("Set SALON_OPERATOR_BOOTSTRAP_FILE to the protected account file");
        }
        Path path = Path.of(file).toAbsolutePath().normalize();
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        for (PosixFilePermission open : Set.of(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE)) {
            if (permissions.contains(open)) {
                throw new IllegalStateException(
                        "The operator bootstrap file must be readable by its owner only (chmod 600)");
            }
        }
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        boolean created = accounts.bootstrap(new OperatorAccounts.AccountInput(
                values.getProperty("email"),
                values.getProperty("displayName"),
                values.getProperty("role"),
                values.getProperty("password"),
                null));
        log.info(
                created
                        ? "Operator bootstrap: the account was created; it enrolls MFA at its first sign-in"
                        : "Operator bootstrap: an account with that email already exists and was left unchanged");
    }
}
