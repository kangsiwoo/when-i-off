# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
# 테스트는 실 PostgreSQL에 붙으므로(CONVENTIONS: Testcontainers 안 씀) 이미지 빌드에서는 제외한다.
# 테스트는 CI와 로컬에서 `./gradlew check`로 돌린다.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
# compose healthcheck가 actuator를 찌르는 데 필요하다 (JRE 이미지에는 curl이 없다).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --create-home --shell /usr/sbin/nologin wio
USER wio
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
