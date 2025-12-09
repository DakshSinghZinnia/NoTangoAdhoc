package com.capability.pdfgeneration.service.service;

import com.capability.pdfgeneration.service.config.S3Config.S3Props;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@ConditionalOnProperty(name = "aws.s3.enabled", havingValue = "true", matchIfMissing = true)
public class StorageService {

    public enum ObjectType { PDF, DOCX }

    private final S3Client s3;
    private final String bucket;
    private final String basePrefix;   // e.g. "documents/"
    private final String pdfPrefix;    // basePrefix + "PDFs/"
    private final String docxPrefix;   // basePrefix + "DOCXs/"

    private static final DateTimeFormatter NAME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneId.of("Asia/Kolkata"));

    public StorageService(S3Client s3, S3Props props) {
        this.s3 = s3;
        this.bucket = Objects.requireNonNull(props.bucket(), "Bucket required");

        // Normalize base prefix
        String p = props.prefix();
        String normalized = (p == null || p.isBlank()) ? "documents/" : (p.endsWith("/") ? p : p + "/");
        this.basePrefix = normalized;
        this.pdfPrefix  = basePrefix + "PDFs/";
        this.docxPrefix = basePrefix + "DOCXs/";
    }

    @PostConstruct
    public void ensurePrefixExists() {
        // Create zero-byte markers so folders appear in consoles
        ensureMarker(pdfPrefix);
        ensureMarker(docxPrefix);
    }

    private void ensureMarker(String prefix) {
        try {
            ListObjectsV2Response list = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).maxKeys(1).build());
            if (!list.contents().isEmpty()) return;

            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(prefix).build(),
                    RequestBody.fromBytes(new byte[0]));
        } catch (S3Exception e) {
            throw new IllegalStateException("Failed to ensure S3 prefix " + prefix + ": "
                    + e.awsErrorDetails().errorMessage(), e);
        }
    }

    // ----------------- Public API (typed) -----------------

    /** Save a PDF under PDFs/ */
    public SaveResult savePdf(byte[] bytes) { return save(ObjectType.PDF, bytes); }

    /** Save a DOCX under DOCXs/ */
    public SaveResult saveDocx(byte[] bytes, @Nullable String desiredName) { return save(ObjectType.DOCX, bytes, desiredName); }

    /** Download a PDF by saved name (without folder). */
    public byte[] getPdfByName(String name) { return getByName(ObjectType.PDF, name); }

    /** Download a DOCX by saved name (without folder). */
    public byte[] getDocxByName(String name) { return getByName(ObjectType.DOCX, name); }

    /** List PDF names under PDFs/ */
    public ListResult listPdfs() { return list(ObjectType.PDF); }

    /** List DOCX names under DOCXs/ */
    public ListResult listDocxs() { return list(ObjectType.DOCX); }

    /** Delete a specific PDF */
    public DeleteOneResult deletePdfByName(String name) { return deleteByName(ObjectType.PDF, name); }

    /** Delete a specific DOCX */
    public DeleteOneResult deleteDocxByName(String name) { return deleteByName(ObjectType.DOCX, name); }

    /** Delete all PDFs */
    public DeleteAllResult deleteAllPdfs() { return deleteAllUnderPrefix(ObjectType.PDF); }

    /** Delete all DOCXs */
    public DeleteAllResult deleteAllDocxs() { return deleteAllUnderPrefix(ObjectType.DOCX); }

    // ----------------- Core helpers -----------------

    // Overload keeps old callers working
    private SaveResult save(ObjectType type, byte[] bytes) {
        return save(type, bytes, null);
    }

    /**
     * If type is DOCX and desiredName is provided, the object is saved using that exact name.
     * PDFs (and DOCX without a name provided) keep the timestamp+UUID naming scheme.
     */
    private SaveResult save(ObjectType type, byte[] bytes, @Nullable String desiredName) {
        final String ext = (type == ObjectType.PDF) ? ".pdf" : ".docx";
        final String keyPrefix = prefixFor(type);

        final boolean isDocx = ".docx".equals(ext);
        final String base;

        if (isDocx && desiredName != null && !desiredName.isBlank()) {
            String sanitized = sanitizeFilename(desiredName.trim());
            // ensure .docx extension but preserve case of the rest of the name
            if (!sanitized.toLowerCase(Locale.ROOT).endsWith(".docx")) {
                sanitized += ".docx";
            }
            base = sanitized; // use the provided name as-is (after minimal sanitization)
        } else {
            base = NAME_FMT.format(Instant.now()) + "-" +
                    UUID.randomUUID().toString().substring(0, 8) + ext;
        }

        final String key = keyPrefix + base;

        PutObjectRequest.Builder req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentLength((long) bytes.length)
                // ensure browsers/downloaders use the same filename
                .contentDisposition("attachment; filename=\"" + base + "\"");

        if (type == ObjectType.PDF) {
            req.contentType("application/pdf");
        } else {
            req.contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }

        PutObjectResponse resp = s3.putObject(req.build(), RequestBody.fromBytes(bytes));
        return new SaveResult(base, key, resp.eTag(), bytes.length, Instant.now());
    }

    /**
     * Remove any path components and disallow directory separators so callers
     * can't escape the prefix. Keeps the visible name intact.
     */
    private static String sanitizeFilename(String name) {
        String justName = name.replace('\\', '/');
        justName = justName.substring(justName.lastIndexOf('/') + 1);
        // Disallow forward slashes to avoid creating unintended "folders" in S3 keys
        return justName.replace("/", "");
    }

    private byte[] getByName(ObjectType type, String name) {
        String key = prefixFor(type) + name;
        try (var in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        } catch (NoSuchKeyException e) {
            throw new NoSuchElementException("No such object: " + name);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) throw new NoSuchElementException("No such object: " + name);
            throw new RuntimeException("Failed to get " + name + ": " + e.awsErrorDetails().errorMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get " + name + ": " + e.getMessage(), e);
        }
    }

    private ListResult list(ObjectType type) {
        String keyPrefix = prefixFor(type);
        String wantedExt = (type == ObjectType.PDF) ? ".pdf" : ".docx";

        List<String> names = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Response resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(keyPrefix).continuationToken(token).build());
            for (S3Object o : resp.contents()) {
                String key = o.key();
                if (key.equals(keyPrefix)) continue; // skip folder marker
                if (!key.toLowerCase().endsWith(wantedExt)) continue;
                names.add(key.substring(keyPrefix.length()));
            }
            token = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (token != null);

        Collections.sort(names);
        return new ListResult(names.size(), names);
    }

    private DeleteOneResult deleteByName(ObjectType type, String name) {
        String key = prefixFor(type) + name;
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new NoSuchElementException("No such object: " + name);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) throw new NoSuchElementException("No such object: " + name);
            throw e;
        }

        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        return new DeleteOneResult(name, true);
    }

    private DeleteAllResult deleteAllUnderPrefix(ObjectType type) {
        String keyPrefix = prefixFor(type);
        List<String> keysToDelete = new ArrayList<>();

        String token = null;
        do {
            ListObjectsV2Response resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(keyPrefix).continuationToken(token).build());
            for (S3Object o : resp.contents()) {
                String key = o.key();
                if (key.equals(keyPrefix)) continue; // keep folder marker
                keysToDelete.add(key);
            }
            token = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (token != null);

        if (keysToDelete.isEmpty()) return new DeleteAllResult(0, 0, List.of());

        int requested = keysToDelete.size();
        int deletedCount = 0;
        List<String> deletedNames = new ArrayList<>();

        for (int i = 0; i < keysToDelete.size(); i += 1000) {
            int end = Math.min(i + 1000, keysToDelete.size());
            List<ObjectIdentifier> batch = new ArrayList<>(end - i);
            for (int j = i; j < end; j++) batch.add(ObjectIdentifier.builder().key(keysToDelete.get(j)).build());

            DeleteObjectsResponse delResp = s3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(batch).build())
                    .build());

            if (delResp.deleted() != null) {
                deletedCount += delResp.deleted().size();
                for (DeletedObject d : delResp.deleted()) {
                    String key = d.key();
                    if (key != null && key.startsWith(keyPrefix)) {
                        deletedNames.add(key.substring(keyPrefix.length()));
                    }
                }
            }
        }

        deletedNames = new ArrayList<>(new LinkedHashSet<>(deletedNames));
        Collections.sort(deletedNames);
        return new DeleteAllResult(requested, deletedCount, deletedNames);
    }

    /** Deletes everything under both DOCXs/ and PDFs/ (keeps folder markers). */
    public CombinedDeleteAllResult deleteAll() {
        DeleteAllResult pdf  = deleteAllPdfs();
        DeleteAllResult docx = deleteAllDocxs();
        return new CombinedDeleteAllResult(
                pdf.requested() + docx.requested(),
                pdf.deleted() + docx.deleted(),
                pdf.deletedNames(),
                docx.deletedNames()
        );
    }

    private String prefixFor(ObjectType type) {
        return (type == ObjectType.PDF) ? pdfPrefix : docxPrefix;
    }

    // DTOs
    public record SaveResult(String name, String key, String eTag, long sizeBytes, Instant uploadedAt) {}
    public record ListResult(int count, List<String> names) {}
    public record DeleteOneResult(String name, boolean deleted) {}
    public record DeleteAllResult(int requested, int deleted, List<String> deletedNames) {}
    public record CombinedDeleteAllResult(
            int requestedTotal,
            int deletedTotal,
            List<String> deletedPdfs,
            List<String> deletedDocxs
    ) {}

}