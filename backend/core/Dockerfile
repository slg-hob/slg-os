# syntax=docker/dockerfile:1
# Core backend image (PRD-0003 SK-16). Build context: the repository root (see deploy/compose.yaml).
# Base images are pinned by digest; the update bot proposes new digests.

FROM eclipse-temurin:25-jdk@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641 AS build
WORKDIR /src
# The Maven wrapper needs unzip: without it, it would fetch the .tar.gz, which the pinned checksum does not cover.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend backend
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl backend/core -am -DskipTests -Djacoco.skip=true package \
 && cp backend/core/target/slg-os-core-*.jar /core.jar

FROM eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin slg
COPY --from=build /core.jar /opt/slg/core.jar
USER 10001:10001
EXPOSE 8080 8081
# Heap at 60 % of the container limit (PRD-0003 SK-16 AC2); trust store and further options come from the compose file.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/opt/slg/core.jar"]
