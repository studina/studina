package com.studina.dinaapi.directus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

@Service
public class DocxPreviewService {

    private static final long CONVERSION_TIMEOUT_SECONDS = 30;

    private final String libreOfficeCommand;

    public DocxPreviewService(
            @org.springframework.beans.factory.annotation.Value(
                    "${libreoffice.command:libreoffice}"
            )
            String libreOfficeCommand) {
        this.libreOfficeCommand = libreOfficeCommand;
    }

    public byte[] convertToPdf(byte[] docxBytes) throws IOException {
        Path temporaryDirectory = Files.createTempDirectory(
                "studina-docx-preview-"
        );

        try {
            Path inputFile = temporaryDirectory.resolve("document.docx");
            Path outputFile = temporaryDirectory.resolve("document.pdf");
            Path conversionLog = temporaryDirectory.resolve("conversion.log");
            Files.write(inputFile, docxBytes);

            Process process = new ProcessBuilder(
                    libreOfficeCommand,
                    "--headless",
                    "--convert-to",
                    "pdf",
                    "--outdir",
                    temporaryDirectory.toString(),
                    inputFile.toString()
            )
                    .redirectErrorStream(true)
                    .redirectOutput(conversionLog.toFile())
                    .start();

            boolean finished;
            try {
                finished = process.waitFor(
                        CONVERSION_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException(
                        "La conversión del DOCX fue interrumpida",
                        exception
                );
            }

            if (!finished) {
                process.destroyForcibly();
                throw new IOException(
                        "La conversión del DOCX superó el tiempo máximo"
                );
            }

            if (process.exitValue() != 0 || !Files.isRegularFile(outputFile)) {
                String details = Files.isRegularFile(conversionLog)
                        ? Files.readString(conversionLog).trim()
                        : "";
                throw new IOException(
                        details.isBlank()
                                ? "LibreOffice no pudo convertir el DOCX"
                                : "LibreOffice no pudo convertir el DOCX: " + details
                );
            }

            return Files.readAllBytes(outputFile);
        } finally {
            deleteRecursively(temporaryDirectory);
        }
    }

    private void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // La limpieza no debe ocultar el resultado de la conversión.
                }
            });
        } catch (IOException ignored) {
            // El directorio temporal del sistema podrá limpiarlo posteriormente.
        }
    }
}
