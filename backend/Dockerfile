# Build from repository root: docker build -f backend/Dockerfile .
ARG DOCKER_REGISTRY=
FROM ${DOCKER_REGISTRY:-}maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY deploy/config/maven-settings-china.xml /root/.m2/settings.xml
COPY backend/pom.xml ./pom.xml

# Cache dependencies separately from sources
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY backend/src ./src
RUN mvn -B -q -DskipTests package

ARG DOCKER_REGISTRY=
FROM ${DOCKER_REGISTRY:-}eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=build /build/target/ai-tool-market-backend-*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
