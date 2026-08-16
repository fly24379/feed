package com.example.feed.service.storage;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "feed.media.s3", name = "enabled", havingValue = "true")
public class S3MediaStorage implements MediaStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3MediaStorage(@Value("${feed.media.s3.endpoint:}") String endpoint,
                          @Value("${feed.media.s3.public-endpoint:}") String publicEndpoint,
                          @Value("${feed.media.s3.region:us-east-1}") String region,
                          @Value("${feed.media.s3.bucket:friend-feed-media}") String bucket,
                          @Value("${feed.media.s3.access-key:}") String accessKey,
                          @Value("${feed.media.s3.secret-key:}") String secretKey,
                          @Value("${feed.media.s3.path-style:true}") boolean pathStyle,
                          @Value("${feed.media.s3.auto-create-bucket:false}") boolean autoCreateBucket) {
        this.bucket = bucket;
        AwsCredentialsProvider credentials = accessKey.isBlank()
                ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        S3Configuration serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyle).build();
        var clientBuilder = S3Client.builder().region(Region.of(region))
                .credentialsProvider(credentials).serviceConfiguration(serviceConfiguration)
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (!endpoint.isBlank()) {
            clientBuilder.endpointOverride(URI.create(endpoint));
        }
        this.s3 = clientBuilder.build();

        var presignerBuilder = S3Presigner.builder().region(Region.of(region))
                .credentialsProvider(credentials).serviceConfiguration(serviceConfiguration);
        String signingEndpoint = publicEndpoint.isBlank() ? endpoint : publicEndpoint;
        if (!signingEndpoint.isBlank()) {
            presignerBuilder.endpointOverride(URI.create(signingEndpoint));
        }
        this.presigner = presignerBuilder.build();
        ensureBucket(autoCreateBucket);
    }

    @Override
    public String provider() {
        return "S3";
    }

    @Override
    public void put(String key, InputStream content, long sizeBytes, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                        .contentType(contentType).build(),
                RequestBody.fromInputStream(content, sizeBytes));
    }

    @Override
    public InputStream read(String key) {
        ResponseInputStream<GetObjectResponse> response = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return response;
    }

    @Override
    public Optional<ObjectMetadata> head(String key) {
        try {
            var response = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new ObjectMetadata(response.contentLength(), response.contentType()));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        s3.deleteObject(builder -> builder.bucket(bucket).key(key));
    }

    @Override
    public Optional<PresignedRequest> presignPut(String key, String contentType, Duration ttl) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).build();
        var request = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl).putObjectRequest(objectRequest).build());
        return Optional.of(new PresignedRequest(request.url().toExternalForm(), "PUT",
                flatten(request.signedHeaders()), Instant.now().plus(ttl)));
    }

    @Override
    public Optional<PresignedRequest> presignGet(String key, String contentType,
                                                  String contentDisposition, Duration ttl) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(bucket).key(key).responseContentType(contentType)
                .responseContentDisposition(contentDisposition).build();
        var request = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl).getObjectRequest(objectRequest).build());
        return Optional.of(new PresignedRequest(request.url().toExternalForm(), "GET",
                Map.of(), Instant.now().plus(ttl)));
    }

    private Map<String, String> flatten(Map<String, java.util.List<String>> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host") && !values.isEmpty()) {
                result.put(name, String.join(",", values));
            }
        });
        return Map.copyOf(result);
    }

    private void ensureBucket(boolean autoCreate) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception exception) {
            if (!autoCreate || exception.statusCode() != 404) {
                throw exception;
            }
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @PreDestroy
    public void close() throws IOException {
        presigner.close();
        s3.close();
    }
}
