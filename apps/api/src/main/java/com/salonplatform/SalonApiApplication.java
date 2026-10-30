package com.salonplatform;

import com.salonplatform.shared.time.UtcTimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class SalonApiApplication {

    /**
     * With this profile the application migrates the database and stops (application-migrate.yaml):
     * a deployment runs it once, from the new version, while the old version keeps serving.
     */
    public static final String MIGRATE_PROFILE = "migrate";

    /**
     * With this profile the application creates one operator account from a protected file and stops
     * (application-operator-bootstrap.yaml): the explicit offline command of
     * docs/architecture/operator-console.md. It never runs as part of an ordinary start.
     */
    public static final String OPERATOR_BOOTSTRAP_PROFILE = "operator-bootstrap";

    public static void main(String[] args) {
        // Before anything reads the time zone; UtcTimeZone does the same for every other start.
        UtcTimeZone.apply();
        ConfigurableApplicationContext context = SpringApplication.run(SalonApiApplication.class, args);
        if (context.getEnvironment().matchesProfiles(MIGRATE_PROFILE)
                || context.getEnvironment().matchesProfiles(OPERATOR_BOOTSTRAP_PROFILE)) {
            // The migrations ran while the context started; a failed one would have failed the start.
            System.exit(SpringApplication.exit(context));
        }
    }
}
