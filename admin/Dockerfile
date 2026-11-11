FROM node:22-alpine AS build
WORKDIR /build
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build
FROM nginx:1.28-alpine
COPY --from=build /build/dist /usr/share/nginx/html
