FROM node:24-alpine

WORKDIR /app

RUN corepack enable && corepack prepare pnpm@12.14.0 --activate

COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY packages/protocol ./packages/protocol
COPY apps/relay ./apps/relay

RUN printf "packages:\n  - apps/relay\n  - packages/*\n" > pnpm-workspace.yaml \
  && pnpm --filter @superpi/relay... install --frozen-lockfile --prod

RUN mkdir -p /data && chown -R node:node /app /data

USER node
ENV NODE_ENV=production \
    SUPERPI_HOST=0.0.0.0 \
    SUPERPI_PORT=8443 \
    SUPERPI_DATA_DIR=/data

EXPOSE 8443
VOLUME ["/data"]

WORKDIR /app/apps/relay
CMD ["node", "src/server/index.ts"]
