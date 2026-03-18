FROM node:22-alpine AS build
WORKDIR /app

# Copiamos solo los manifests primero para cachear node_modules
# Si no cambian package.json ni package-lock.json, Docker reutiliza esta capa
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# Ahora copiamos el resto del código
COPY . .
RUN npm run build

FROM nginx:1.27.4-alpine AS deploy
WORKDIR /usr/share/nginx/html/

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY run.sh /
COPY --from=build /app/dist/ .

EXPOSE 80

CMD ["sh", "/run.sh"]