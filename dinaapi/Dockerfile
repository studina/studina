FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon --max-workers=2 --no-parallel \
    -Dorg.gradle.jvmargs="-Xmx1g -XX:MaxMetaspaceSize=384m"

FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "app.jar"]
