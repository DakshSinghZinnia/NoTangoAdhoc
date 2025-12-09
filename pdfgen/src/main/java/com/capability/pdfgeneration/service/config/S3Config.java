package com.capability.pdfgeneration.service.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Optional;

@Configuration
@ConditionalOnProperty(name = "aws.s3.enabled", havingValue = "true", matchIfMissing = true)
public class S3Config {

    // Bucket + prefix (“folder”)
    @Value("${aws.s3.bucket}")
    private String bucket;

    // NOTE: keep the exact name with the space as you requested
    @Value("${aws.s3.prefix}")
    private String prefix;

    @Value("${aws.region}")
    private String region;

    @Bean
    public S3Client s3Client() {
        // Try creds from .env (dev) → env/system → default chain
        Credentials creds = loadCredsFromDotenv().orElseGet(this::loadCredsFromEnv);
        AwsCredentialsProvider provider = creds != null
                ? (creds.sessionToken == null
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(creds.accessKey, creds.secretKey))
                : StaticCredentialsProvider.create(AwsSessionCredentials.create(creds.accessKey, creds.secretKey, creds.sessionToken)))
                : DefaultCredentialsProvider.create();

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(provider)
                .build();
    }

    @Bean
    public S3Props s3Props() {
        // expose bucket & prefix to services
        return new S3Props(bucket, prefix);
    }

    public record S3Props(String bucket, String prefix) {}

    /* ---------- helpers ---------- */

    private Optional<Credentials> loadCredsFromDotenv() {
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            String ak = dotenv.get("AWS_ACCESS_KEY_ID");
            String sk = dotenv.get("AWS_SECRET_ACCESS_KEY");
            String st = dotenv.get("AWS_SESSION_TOKEN");
            if (ak != null && sk != null) return Optional.of(new Credentials(ak, sk, st));
        } catch (Throwable ignored) {}
        return Optional.empty();
    }

    private Credentials loadCredsFromEnv() {
        String ak = System.getenv("AWS_ACCESS_KEY_ID");
        String sk = System.getenv("AWS_SECRET_ACCESS_KEY");
        String st = System.getenv("AWS_SESSION_TOKEN");
        if (ak != null && sk != null) return new Credentials(ak, sk, st);
        return null;
    }

    private static final class Credentials {
        final String accessKey, secretKey, sessionToken;
        Credentials(String ak, String sk, String st) { this.accessKey = ak; this.secretKey = sk; this.sessionToken = st; }
    }
}