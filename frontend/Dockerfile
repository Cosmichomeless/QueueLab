# Imagen de producción del dashboard de QueueLab (Next.js, salida `standalone`).
#
#   docker build -t queuelab-dashboard frontend
#   docker run --rm -p 3000:3000 -e QUEUELAB_API_URL=http://localhost:8080 queuelab-dashboard
#
# La URL de la API NO se fija al construir: se lee de `QUEUELAB_API_URL` al arrancar el contenedor, así que la
# misma imagen sirve en cualquier entorno (ver frontend/README.md y docs/containers-frontend.md).

ARG NODE_VERSION=22

# --- Dependencias: capa cacheada mientras no cambien package*.json
FROM node:${NODE_VERSION}-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# --- Compilación
FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# --- Runtime: solo el servidor trazado, sin node_modules completo ni código fuente
FROM node:${NODE_VERSION}-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# La imagen de node ya trae el usuario `node` (uid 1000): el proceso no corre como root.
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static

USER node
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/jobs" || exit 1

CMD ["node", "server.js"]
