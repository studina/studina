package com.studina.dinaapi.minio;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;

@RestController
@RequestMapping(path="/files", produces="application/json")
public class FileController {

    @Autowired
    private MinioClient minioClient;
    @Value("${minio.bucket.name}")
    private String bucketName;

    public FileController(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @PostMapping("/upload")
    public void uploadFile(String bucketName, String objectName, InputStream inputStream, String contentType) {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            minioClient.putObject(
                PutObjectArgs.builder().bucket(bucketName).object(objectName).stream(
                        inputStream, (long) inputStream.available(), (long) -1)
                        .contentType(contentType)
                        .build());
        } catch (Exception e) {
            throw new RuntimeException("Error occurred: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    public ResponseEntity<HashMap<String, List<String>>> listFiles() {
        try {
            HashMap<String, List<String>> response = new HashMap<>();
            List<String> fileNames = new ArrayList<>();
            Iterable<Result<Item>> items = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucketName).build()
                );
            for (Result<Item> item : items) {
                fileNames.add(item.get().objectName());
            }

            response.put("data", fileNames);
            response.put("errors", new ArrayList<String>());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            HashMap<String, List<String>> response = new HashMap<>();
            List<String> errors = new ArrayList<>();

            errors.add(e.getMessage());

            response.put("data", new ArrayList<String>());
            response.put("errors", errors);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/download/{filename}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) {
        try {
            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucketName).object(filename).build()
            );
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(stream));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }
}