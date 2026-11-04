FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY package.json ./
COPY server ./server
COPY web ./web
# Render define PORT (10000 por defecto); el servidor la lee de la variable de entorno.
EXPOSE 10000
USER node
CMD ["node", "server/server.js"]
