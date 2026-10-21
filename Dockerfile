FROM eclipse-temurin:17-jre-alpine
# pg_basebackup 17+ for the scheduled Postgres backups (UNISAGE-102); it must not be older than the server.
RUN apk add --no-cache postgresql17-client
WORKDIR /app
COPY target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
