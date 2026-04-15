FROM node:20-alpine
RUN apk add --no-cache curl bash jq tar gzip
WORKDIR /agent
COPY package.json ./
RUN npm install --production
COPY . .
CMD ["npm", "start"]
