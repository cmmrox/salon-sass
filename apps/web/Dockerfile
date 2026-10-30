# syntax=docker/dockerfile:1
# The web app's production image: Next.js's standalone output, served by its own minimal server
# (decision 030). Built by infra/scripts/images-build.sh from the repository root, the pnpm
# workspace, filtered by apps/web/Dockerfile.dockerignore.
#
# It runs as an unprivileged user, holds no secret and no environment file: every setting arrives
# from the environment the container is started with (infra/stack/compose.yaml).

ARG NODE_IMAGE=node:24.21.0-alpine3.24

FROM ${NODE_IMAGE} AS build
WORKDIR /workspace
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0 \
    NEXT_TELEMETRY_DISABLED=1
RUN corepack enable
# The workspace's manifests first, so that the dependencies are a layer of their own.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/
COPY packages/api-contracts/package.json packages/api-contracts/
COPY packages/format/package.json packages/format/
RUN --mount=type=cache,id=salon-pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY packages packages
COPY apps/web apps/web
RUN pnpm --filter @salon/web build

FROM ${NODE_IMAGE}
ARG VERSION
ARG COMMIT
LABEL org.opencontainers.image.title="salon-web" \
      org.opencontainers.image.description="The platform's web app" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${COMMIT}"
# The server needs Node.js alone. The package managers the base image carries are removed, so that
# nothing in the image installs or runs a package.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
           /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
           /opt/yarn-* /usr/local/bin/yarn /usr/local/bin/yarnpkg \
 && addgroup -S -g 10001 salon && adduser -S -D -H -u 10001 -G salon -h /app salon
WORKDIR /app
# The files belong to root and cannot be changed by the user that runs them; only the framework's
# cache folder is the user's.
COPY --from=build /workspace/apps/web/.next/standalone/ ./
COPY --from=build /workspace/apps/web/.next/static/ ./apps/web/.next/static/
COPY --from=build /workspace/apps/web/public/ ./apps/web/public/
RUN mkdir -p apps/web/.next/cache && chown salon:salon apps/web/.next/cache
USER 10001:10001
# Inside the container: every interface, one port. NEXT_MANUAL_SIG_HANDLE hands SIGTERM to the
# application, which drains before it stops (src/lib/observability/drain.ts).
ENV NODE_ENV=production \
    HOSTNAME=0.0.0.0 \
    PORT=3000 \
    NEXT_TELEMETRY_DISABLED=1 \
    NEXT_MANUAL_SIG_HANDLE=true
EXPOSE 3000
HEALTHCHECK --interval=2s --timeout=2s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:3000/healthz || exit 1
CMD ["node", "apps/web/server.js"]
