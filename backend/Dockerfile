# The web dashboard is built first; Vite writes it to ../src/main/resources/static relative to the dashboard folder.
FROM node:22-slim AS web
WORKDIR /web/dashboard
COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY dashboard/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
COPY --from=web /web/src/main/resources/static ./src/main/resources/static
RUN mvn -B -q -DskipTests package && cp target/finance-aggregator-backend-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0555 /app/docker-entrypoint.sh
USER app

# Sized for a 512 MB container.
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_ADDRESS=0.0.0.0 \
    JAVA_OPTS="-Xmx240m -Xss512k -XX:MaxMetaspaceSize=100m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
EXPOSE 10000
ENTRYPOINT ["/app/docker-entrypoint.sh"]
