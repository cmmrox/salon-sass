# QA-only fixture image, based on the tested next-version product image.
ARG BASE_IMAGE
FROM ${BASE_IMAGE} AS source
FROM eclipse-temurin:25.0.4.1_1-jdk-alpine-3.24 AS fixture
WORKDIR /work
COPY --from=source /app/salon-api.jar salon-api.jar
COPY V8__qa_rollout_probe.sql db/migration/V8__qa_rollout_probe.sql
RUN jar --update --no-manifest --file salon-api.jar -C /work db/migration/V8__qa_rollout_probe.sql
FROM ${BASE_IMAGE}
USER 0
COPY --from=fixture --chown=0:0 /work/salon-api.jar /app/salon-api.jar
LABEL qa.fixture="F01 additive migration V8, never publish"
USER 10001:10001
