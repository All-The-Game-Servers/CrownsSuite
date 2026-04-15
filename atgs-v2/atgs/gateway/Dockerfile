FROM maven:3.9.9-eclipse-temurin-21 AS plugin-builder
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache bash curl jq
WORKDIR /opt/atgs
COPY --from=plugin-builder /src/target/atgs-gateway-plugin-1.0.0.jar /opt/atgs/atgs-gateway-plugin.jar
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
EXPOSE 25577
ENTRYPOINT ["/entrypoint.sh"]
