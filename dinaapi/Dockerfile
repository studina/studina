FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew bootJar -x test

FROM eclipse-temurin:21-jre

WORKDIR /app

# DocxPreviewService usa el ejecutable `libreoffice` para convertir documentos
# DOCX a PDF. Se instala también en la imagen final, no sólo en CI, para que la
# previsualización funcione al levantar el proyecto con Docker.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends libreoffice-writer \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
