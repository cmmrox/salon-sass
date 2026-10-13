# syntax=docker/dockerfile:1
# The API's production image. Built by infra/scripts/images-build.sh, which gives it the version and
# the commit; the build context is apps/api, filtered by apps/api/.dockerignore.
#
# It runs as an unprivileged user, holds no secret and no environment file: every setting arrives
# from the environment the container is started with (infra/stack/compose.yaml).

ARG JDK_IMAGE=eclipse-temurin:25.0.4.1_1-jdk-alpine-3.24
ARG JRE_IMAGE=eclipse-temurin:25.0.4.1_1-jre-alpine-3.24

FROM ${JDK_IMAGE} AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
ARG VERSION
ARG COMMIT
# There is no repository in the build: the commit `meta` reports is the one the build was given.
RUN test -n "$VERSION" && test -n "$COMMIT" \
 && printf 'git.commit.id=%s\ngit.commit.id.abbrev=%.7s\n' "$COMMIT" "$COMMIT" > src/main/resources/git.properties
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --quiet -DskipTests -Dmaven.gitcommitid.skip=true -Drevision="$VERSION" package \
 && java -Djarmode=tools -jar target/salon-api.jar extract --layers --destination /build/layers

FROM ${JRE_IMAGE}
ARG VERSION
ARG COMMIT
LABEL org.opencontainers.image.title="salon-api" \
      org.opencontainers.image.description="The platform's API" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${COMMIT}"
RUN addgroup -S -g 10001 salon && adduser -S -D -H -u 10001 -G salon -h /app salon
WORKDIR /app
# Least-changing layer first. The files belong to root and cannot be changed by the user that runs them.
COPY --from=build /build/layers/dependencies/ ./
COPY --from=build /build/layers/spring-boot-loader/ ./
COPY --from=build /build/layers/snapshot-dependencies/ ./
COPY --from=build /build/layers/application/ ./
USER 10001:10001
# Inside the container: every interface, one port.
ENV SALON_API_PORT=8080 \
    SALON_LISTEN_ON_NETWORK=true
EXPOSE 8080
HEALTHCHECK --interval=2s --timeout=2s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "salon-api.jar"]
