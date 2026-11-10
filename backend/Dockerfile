FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
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
