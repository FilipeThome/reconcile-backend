# syntax=docker/dockerfile:1
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test \
    && mv build/libs/*-SNAPSHOT.jar /src/app.jar

FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget \
    && addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
