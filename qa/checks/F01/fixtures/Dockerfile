# QA-only fixture image, based on the tested next-version product image.
# The build context contains only the jar patched and byte-checked by S05-fixture-build.sh.
ARG BASE_IMAGE=salon-api:qa-f01-next-product
FROM ${BASE_IMAGE}
USER 0
COPY --chown=0:0 salon-api.jar /app/salon-api.jar
LABEL qa.fixture="F01 additive migration V8, never publish"
USER 10001:10001
