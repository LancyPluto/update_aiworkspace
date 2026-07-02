# Build from repository root: docker build -f backend/Dockerfile .
ARG DOCKER_REGISTRY=
FROM ${DOCKER_REGISTRY:-}maven:3.9-eclipse-temurin-17 AS build

ARG HTTP_PROXY=
ARG HTTPS_PROXY=
ARG NO_PROXY=localhost,127.0.0.1

ENV HTTP_PROXY=${HTTP_PROXY}
ENV HTTPS_PROXY=${HTTPS_PROXY}
ENV NO_PROXY=${NO_PROXY}

WORKDIR /build

COPY deploy/config/maven-settings-china.xml /root/.m2/settings.xml
COPY backend/pom.xml ./pom.xml

# Official Maven Central; outbound via mihomo proxy (HTTP_PROXY) on production builds.
RUN mvn -B dependency:go-offline -DskipTests || true

COPY backend/src ./src
RUN mvn -B -DskipTests package

ARG DOCKER_REGISTRY=
FROM ${DOCKER_REGISTRY:-}eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=build /build/target/ai-tool-market-backend-*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
