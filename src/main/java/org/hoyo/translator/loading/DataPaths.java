package org.hoyo.translator.loading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Resolves where the (now runtime-refreshable) asset/TextMap files and their
 * metadata live on disk, and seeds that directory from the bundled classpath
 * resources on first run so the app still works out-of-the-box with whatever
 * was fetched at build time.
 */
@Component
public class DataPaths {

    private static final Logger log = LoggerFactory.getLogger(DataPaths.class);

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public DataPaths(@Value("${translator.data.directory:./data}") String baseDir, ObjectMapper objectMapper) {
        this.baseDir = Paths.get(baseDir);
        this.objectMapper = objectMapper;
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path assetsDir() {
        return baseDir.resolve("assets");
    }

    public Path textMapsDir() {
        return baseDir.resolve("textMaps");
    }

    public Path assetMetadataFile() {
        return assetsDir().resolve(".asset_metadata.json");
    }

    public Path textMapMetadataFile() {
        return textMapsDir().resolve(".sync_metadata.json");
    }

    public Map<String, String> readAssetMetadata() {
        return readMetadata(assetMetadataFile());
    }

    public Map<String, String> readTextMapMetadata() {
        return readMetadata(textMapMetadataFile());
    }

    private Map<String, String> readMetadata(Path file) {
        try {
            if (!Files.exists(file)) {
                return Map.of();
            }
            try (InputStream in = Files.newInputStream(file)) {
                return objectMapper.readValue(in, new TypeReference<>() {});
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read metadata file " + file, e);
        }
    }

    /**
     * If the data directory doesn't exist yet, populate it with the asset/TextMap
     * files and metadata bundled into the jar at build time (via the Gradle
     * syncAssets/syncTextMaps tasks), so the runtime refresh has a known-good
     * starting point.
     */
    public synchronized void ensureSeeded() throws IOException {
        if (Files.exists(baseDir)) {
            return;
        }

        log.info("Data directory [{}] does not exist - seeding it from bundled resources...", baseDir.toAbsolutePath());

        Files.createDirectories(assetsDir());
        Files.createDirectories(textMapsDir());

        copyClasspathResource("assets/.asset_metadata.json", assetMetadataFile());
        for (String fileName : readMetadata(assetMetadataFile()).keySet()) {
            copyClasspathResource("assets/" + fileName, assetsDir().resolve(fileName));
        }

        copyClasspathResource("textMaps/.sync_metadata.json", textMapMetadataFile());
        for (String fileName : readMetadata(textMapMetadataFile()).keySet()) {
            copyClasspathResource("textMaps/" + fileName, textMapsDir().resolve(fileName));
        }

        log.info("Seeding complete - data directory ready at [{}]", baseDir.toAbsolutePath());
    }

    private void copyClasspathResource(String classpathLocation, Path target) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            log.warn("Bundled resource [{}] not found, skipping seed for it", classpathLocation);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("Seeded [{}] -> [{}]", classpathLocation, target);
    }
}
