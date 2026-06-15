package org.hoyo.translator.loading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Runtime port of {@code scripts/sync_assets.py} and {@code scripts/sync_files.py}.
 * Downloads the latest HSR asset/TextMap files into {@link DataPaths#baseDir()} and
 * updates the local metadata files so {@link org.hoyo.translator.RedisDataLoaderService}
 * can hash-check and load only what changed.
 */
@Service
public class AssetFetchService {

    private static final Logger log = LoggerFactory.getLogger(AssetFetchService.class);

    private static final String USER_AGENT = "Mozilla/5.0";

    private static final Map<String, String> GITHUB_FILES = Map.of(
            "hsr.json", "https://raw.githubusercontent.com/EnkaNetwork/API-docs/master/store/hsr/hsr.json",
            "relics.json", "https://raw.githubusercontent.com/EnkaNetwork/API-docs/master/store/hsr/relics.json"
    );

    private static final String GITLAB_PROJECT = "Dimbreath%2Fturnbasedgamedata";
    private static final String GITLAB_BRANCH = "main";
    private static final String ITEM_CONFIG_RELIC_FILE = "ItemConfigRelic.json";

    private final DataPaths dataPaths;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AssetFetchService(DataPaths dataPaths, ObjectMapper objectMapper) {
        this.dataPaths = dataPaths;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Downloads the latest assets/TextMaps (if changed) into the data directory and
     * updates the associated metadata files. Does not touch Redis - that's handled
     * separately by {@link org.hoyo.translator.RedisDataLoaderService#refreshData()}.
     */
    public void fetchLatest() throws IOException {
        dataPaths.ensureSeeded();
        fetchAssets();
        fetchTextMaps();
    }

    private void fetchAssets() throws IOException {
        log.info("Checking for fresh asset files...");

        Map<String, String> metadata = dataPaths.readAssetMetadata();
        Map<String, String> newMetadata = new LinkedHashMap<>();
        int downloaded = 0, updated = 0, unchanged = 0;

        for (Map.Entry<String, String> entry : GITHUB_FILES.entrySet()) {
            String fileName = entry.getKey();
            byte[] content = download(entry.getValue());
            validateJson(content, fileName);

            String remoteHash = sha256(content);
            String localHash = metadata.get(fileName);
            newMetadata.put(fileName, remoteHash);

            if (remoteHash.equals(localHash)) {
                log.info("  [{}] up-to-date (hash={})", fileName, shortHash(remoteHash));
                unchanged++;
            } else {
                Files.write(dataPaths.assetsDir().resolve(fileName), content);
                if (localHash == null) {
                    log.info("  [{}] downloaded for the first time (hash={})", fileName, shortHash(remoteHash));
                    downloaded++;
                } else {
                    log.info("  [{}] updated ({} -> {})", fileName, shortHash(localHash), shortHash(remoteHash));
                    updated++;
                }
            }
        }

        // ItemConfigRelic.json from GitLab
        String encodedPath = encodePathSegment("ExcelOutput/" + ITEM_CONFIG_RELIC_FILE);
        String url = "https://gitlab.com/api/v4/projects/" + GITLAB_PROJECT
                + "/repository/files/" + encodedPath + "/raw?ref=" + GITLAB_BRANCH;

        byte[] content = download(url);
        validateJson(content, ITEM_CONFIG_RELIC_FILE);

        String remoteHash = sha256(content);
        String localHash = metadata.get(ITEM_CONFIG_RELIC_FILE);
        newMetadata.put(ITEM_CONFIG_RELIC_FILE, remoteHash);

        if (remoteHash.equals(localHash)) {
            log.info("  [{}] up-to-date (hash={})", ITEM_CONFIG_RELIC_FILE, shortHash(remoteHash));
            unchanged++;
        } else {
            Files.write(dataPaths.assetsDir().resolve(ITEM_CONFIG_RELIC_FILE), content);
            if (localHash == null) {
                log.info("  [{}] downloaded for the first time (hash={})", ITEM_CONFIG_RELIC_FILE, shortHash(remoteHash));
                downloaded++;
            } else {
                log.info("  [{}] updated ({} -> {})", ITEM_CONFIG_RELIC_FILE, shortHash(localHash), shortHash(remoteHash));
                updated++;
            }
        }

        writeMetadata(dataPaths.assetMetadataFile(), newMetadata);
        log.info("Asset file check complete: {} downloaded, {} updated, {} unchanged", downloaded, updated, unchanged);
    }

    private void fetchTextMaps() throws IOException {
        log.info("Checking for fresh TextMap files...");

        Map<String, String> metadata = dataPaths.readTextMapMetadata();
        Map<String, String> newMetadata = new LinkedHashMap<>();
        int downloaded = 0, updated = 0, unchanged = 0;

        String treeUrl = "https://gitlab.com/api/v4/projects/" + GITLAB_PROJECT
                + "/repository/tree?path=TextMap&ref=" + GITLAB_BRANCH + "&per_page=100";

        JsonNode tree = objectMapper.readTree(download(treeUrl));

        for (JsonNode item : tree) {
            if (!"blob".equals(item.path("type").asText())) {
                continue;
            }

            String fileName = item.path("name").asText();
            if (fileName.contains("Main") || !fileName.endsWith(".json")) {
                continue;
            }

            String remoteBlob = item.path("id").asText();
            String localBlob = metadata.get(fileName);
            newMetadata.put(fileName, remoteBlob);

            if (remoteBlob.equals(localBlob)) {
                log.info("  [{}] up-to-date (blob={})", fileName, shortHash(remoteBlob));
                unchanged++;
                continue;
            }

            String rawUrl = "https://gitlab.com/Dimbreath/turnbasedgamedata/-/raw/" + GITLAB_BRANCH + "/TextMap/" + fileName;
            byte[] content = download(rawUrl);
            validateJson(content, fileName);

            Files.write(dataPaths.textMapsDir().resolve(fileName), content);

            if (localBlob == null) {
                log.info("  [{}] downloaded for the first time (blob={})", fileName, shortHash(remoteBlob));
                downloaded++;
            } else {
                log.info("  [{}] updated (blob {} -> {})", fileName, shortHash(localBlob), shortHash(remoteBlob));
                updated++;
            }
        }

        removeObsoleteTextMaps(newMetadata.keySet());
        writeMetadata(dataPaths.textMapMetadataFile(), newMetadata);
        log.info("TextMap file check complete: {} downloaded, {} updated, {} unchanged", downloaded, updated, unchanged);
    }

    private void removeObsoleteTextMaps(Set<String> currentFiles) throws IOException {
        if (!Files.exists(dataPaths.textMapsDir())) {
            return;
        }
        try (Stream<Path> stream = Files.list(dataPaths.textMapsDir())) {
            Set<Path> toRemove = new LinkedHashSet<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().contains("Main"))
                    .filter(p -> !currentFiles.contains(p.getFileName().toString()))
                    .forEach(toRemove::add);

            for (Path path : toRemove) {
                log.info("  removing obsolete TextMap file [{}]", path.getFileName());
                Files.deleteIfExists(path);
            }
        }
    }

    private byte[] download(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() / 100 != 2) {
                throw new IOException("Request to " + url + " failed with status " + response.statusCode());
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }
    }

    private void validateJson(byte[] content, String fileName) throws IOException {
        try {
            objectMapper.readTree(content);
        } catch (Exception e) {
            throw new IOException(fileName + " does not contain valid JSON", e);
        }
    }

    private void writeMetadata(Path file, Map<String, String> metadata) throws IOException {
        Files.createDirectories(file.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), metadata);
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String shortHash(String hash) {
        return hash.length() > 10 ? hash.substring(0, 10) + "..." : hash;
    }

    private String encodePathSegment(String path) {
        return java.net.URLEncoder.encode(path, StandardCharsets.UTF_8);
    }
}
