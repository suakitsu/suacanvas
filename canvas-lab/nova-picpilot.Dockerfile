FROM node:22-slim AS frontend-builder

WORKDIR /app

COPY third_party/nova-image-studio/package.json ./package.json
COPY third_party/nova-image-studio/frontend/package.json third_party/nova-image-studio/frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci

COPY third_party/nova-image-studio/frontend/ ./frontend/
COPY canvas-lab/picpilot-canvas-bridge.js canvas-lab/picpilot-canvas-brand.js ./frontend/public/

ENV NEXT_PUBLIC_PICPILOT_EMBEDDED=true
RUN cd frontend && npm run build

FROM node:22-slim AS backend-deps
WORKDIR /app/backend
RUN apt-get update \
  && apt-get install -y --no-install-recommends python3 make g++ \
  && rm -rf /var/lib/apt/lists/*
COPY third_party/canvas-lab/nova-image-studio/backend/package.json third_party/canvas-lab/nova-image-studio/backend/package-lock.json ./
RUN npm ci --omit=dev

# Build the pinned backend from source so the source offer matches this image.
# Run scripts/fetch-canvas-lab-upstreams.sh before building.
FROM node:22-slim AS production
WORKDIR /app
ENV NODE_ENV=production
COPY third_party/canvas-lab/nova-image-studio/backend/ ./backend/
COPY --from=backend-deps /app/backend/node_modules/ ./backend/node_modules/
COPY --from=frontend-builder /app/frontend/out/ /app/frontend/out/
COPY third_party/nova-image-studio/LICENSE /app/LICENSE
RUN mkdir -p /app/backend/data
EXPOSE 3000
CMD ["node", "backend/server.js"]
