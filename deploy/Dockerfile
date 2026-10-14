# PLASMACO Operations server image. Build the jar first (./gradlew :backend:bootJar), then from the repository root:
#   docker build -f deploy/Dockerfile -t plasmaco-server .
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 plasmaco
WORKDIR /app
COPY backend/build/libs/plasmaco-server.jar /app/plasmaco-server.jar
USER plasmaco
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/plasmaco-server.jar"]
