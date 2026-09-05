# 두 단계 빌드 — 로컬에 JDK 25가 없어도 `docker compose --profile stack up --build` 한 줄로 뜬다.
# 빌드 컨텍스트는 저장소 루트다(gradlew·settings가 필요). compose의 build.context가 그렇게 잡는다.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY app/build.gradle.kts app/
COPY mock-supplier/build.gradle.kts mock-supplier/
COPY app/src app/src
# Gradle 캐시를 BuildKit 캐시 마운트에 둔다 — 의존성을 빌드마다 다시 받지 않는다. 테스트는 이미지 빌드에서 돌리지 않는다(Testcontainers가 Docker를 요구한다).
RUN --mount=type=cache,target=/root/.gradle ./gradlew :app:bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/app/build/libs/*.jar app.jar
EXPOSE 8080
# Java 25는 Netty의 네이티브 로딩에 restricted method 경고를 낸다 — bootRun·테스트와 같은 JVM 인자
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
