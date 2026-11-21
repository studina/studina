FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon --max-workers=2 --no-parallel \
    -Dorg.gradle.jvmargs="-Xmx1g -XX:MaxMetaspaceSize=384m"

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
