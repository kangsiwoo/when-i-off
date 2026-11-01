# syntax=docker/dockerfile:1

# 베이스 이미지는 패치 버전·배포판까지 고정해 같은 커밋이 언제 빌드해도 같은 JDK/OS가 되게 한다.
# 올릴 때는 두 FROM을 함께 바꾼다.
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /src
# gradle.properties(jvmargs·빌드 캐시 설정)가 빠지면 로컬·CI와 다른 설정으로 빌드된다.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
# 테스트는 실 PostgreSQL에 붙으므로(CONVENTIONS: Testcontainers 안 씀) 이미지 빌드에서는 제외한다.
# 테스트는 CI와 로컬에서 `./gradlew check`로 돌린다.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21.0.13_9-jre-noble
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
