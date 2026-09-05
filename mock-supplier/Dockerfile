FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY app/build.gradle.kts app/
COPY mock-supplier/build.gradle.kts mock-supplier/
COPY mock-supplier/src mock-supplier/src
RUN --mount=type=cache,target=/root/.gradle ./gradlew :mock-supplier:bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/mock-supplier/build/libs/*.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
