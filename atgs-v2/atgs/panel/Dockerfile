# ==============================================================
#  ATGS Panel — A product of XKStudios
#  Includes Java JRE so it can run Fabric/Forge installers
#  during instance creation.
# ==============================================================
FROM node:20-alpine

RUN apk add --no-cache curl bash tar gzip python3 make g++

WORKDIR /app
COPY package.json ./
RUN npm install --production
COPY . .

ENV NODE_ENV=production

EXPOSE 8080
CMD ["npm", "start"]
