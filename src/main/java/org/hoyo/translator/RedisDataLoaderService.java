package org.hoyo.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.hoyo.translator.loading.AssetFetchService;
import org.hoyo.translator.loading.DataLoadingStatus;
import org.hoyo.translator.loading.DataPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@Service
public class RedisDataLoaderService {

    private static final Logger log = LoggerFactory.getLogger(RedisDataLoaderService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DataLoadingStatus loadingStatus;
    private final TaskExecutor dataLoaderExecutor;
    private final DataPaths dataPaths;
    private final AssetFetchService assetFetchService;

    // Owner token for this JVM's refresh lock - distinct per instance, so each
    // instance only ever releases a lock it actually holds.
    private final String lockOwnerId = UUID.randomUUID().toString();

    @Value("${translator.data.refresh-lock-key:translator:refresh:lock}")
    private String refreshLockKey;

    @Value("${translator.data.refresh-lock-ttl-seconds:3600}")
    private long refreshLockTtlSeconds;

    public RedisDataLoaderService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                   DataLoadingStatus loadingStatus, TaskExecutor dataLoaderExecutor,
                                   DataPaths dataPaths, AssetFetchService assetFetchService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.loadingStatus = loadingStatus;
        this.dataLoaderExecutor = dataLoaderExecutor;
        this.dataPaths = dataPaths;
        this.assetFetchService = assetFetchService;
    }

    /**
     * Configuration record for each file we want to load.
     */
    public record FileConfig(String filePath, String keyPrefix, String arrayIdField) {}

    /**
     * Kicks off the initial data load in the background so the application becomes
     * available immediately instead of blocking startup for hours.
     */
    @PostConstruct
    public void scheduleInitialLoad() {
        dataLoaderExecutor.execute(this::refreshData);
    }

    /**
     * Checks all asset/TextMap files for changes and loads any that changed into Redis.
     * Safe to call repeatedly (e.g. from a manual refresh endpoint or a scheduler) -
     * if a load is already in progress, this call is a no-op.
     */
    public void refreshData() {
        if (!loadingStatus.tryStart()) {
            log.info("Redis data import already in progress, skipping this request.");
            return;
        }

        // DataLoadingStatus's AtomicBoolean only guards against a second concurrent
        // run on this same JVM. With multiple Translator instances sharing the same
        // Redis (e.g. an extra instance on philia alongside orexis's), a Redis-level
        // lock is needed too, otherwise both instances can run the full file-load
        // loop at once on the same cron tick or on simultaneous startup.
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(refreshLockKey, lockOwnerId, Duration.ofSeconds(refreshLockTtlSeconds));
        if (acquired == null || !acquired) {
            log.info("Another instance currently holds the refresh lock ({}), skipping this request.", refreshLockKey);
            loadingStatus.markFinished(true, null);
            return;
        }

        Instant overallStart = Instant.now();
        try {
            log.info("=== Starting Redis data import check ===");

            log.info("Checking for fresh asset/TextMap files...");
            assetFetchService.fetchLatest();

            // Define all files to load here
            List<FileConfig> filesToLoad = new ArrayList<>(List.of(
                    new FileConfig("assets/hsr.json", "hsr", null),
                    new FileConfig("assets/relics.json", "relics", null),
                    new FileConfig("assets/ItemConfigRelic.json", "relic_config", "ID"),
                    // avatars.json is a plain object keyed by avatarId (not an array),
                    // same shape as relics.json's top level — flattens to
                    // avatar_config:{avatarId}:AvatarName:Hash etc., which
                    // HonkaiTranslateService.getAvatarCatalog scans the same way
                    // getRelicCatalog scans relics:Sets:*:Name.
                    new FileConfig("assets/avatars.json", "avatar_config", null)
            ));

            try (Stream<Path> stream = Files.list(dataPaths.textMapsDir())) {

                stream.filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.endsWith(".json"))
                        .filter(name -> !name.startsWith(".")) // skips .sync_metadata.json
                        .forEach(fileName -> {

                            String configName = fileName
                                    .replace(".json", "")
                                    .replaceAll("_[0-9]+$", "")
                                    .replaceAll("[0-9]+$", "");

                            configName = Character.toLowerCase(configName.charAt(0))
                                    + configName.substring(1);

                            filesToLoad.add(
                                    new FileConfig(
                                            "textMaps/" + fileName,
                                            configName,
                                            null
                                    )
                            );
                        });
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }

            // Process each file universally
            Map<String, String> textMapHashes = dataPaths.readTextMapMetadata();
            Map<String, String> assetHashes = dataPaths.readAssetMetadata();

            log.info("Found {} files to check ({} TextMap files)", filesToLoad.size(), filesToLoad.size() - 4);

            int fileIndex = 0;
            for (FileConfig config : filesToLoad) {
                fileIndex++;

                String fileName = Path.of(config.filePath())
                        .getFileName()
                        .toString();

                log.info("[{}/{}] Checking [{}]...", fileIndex, filesToLoad.size(), fileName);

                if (config.filePath().startsWith("textMaps/")) {

                    processFileIfChanged(
                            config,
                            fileName,
                            textMapHashes.get(fileName),
                            "textmap:versions"
                    );

                } else {

                    processFileIfChanged(
                            config,
                            fileName,
                            assetHashes.get(fileName),
                            "asset:versions"
                    );
                }
            }

            Duration elapsed = Duration.between(overallStart, Instant.now());
            log.info("=== Redis data import check complete (took {}) ===", formatDuration(elapsed));
            loadingStatus.markFinished(true, null);
        } catch (Exception e) {
            log.error("Redis data import check failed", e);
            loadingStatus.markFinished(false, e.getMessage());
        } finally {
            releaseRefreshLockIfOwned();
        }
    }

    /**
     * Only deletes the lock if it's still ours - if our TTL already expired and
     * another instance grabbed it, we must not delete their lock out from under them.
     */
    private void releaseRefreshLockIfOwned() {
        if (lockOwnerId.equals(redisTemplate.opsForValue().get(refreshLockKey))) {
            redisTemplate.delete(refreshLockKey);
        }
    }


    private void loadUniversalJsonToRedis(FileConfig config) {
        Path filePath = dataPaths.baseDir().resolve(config.filePath());
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            JsonNode rootNode = objectMapper.readTree(inputStream);
            int[] count = {0};
            Instant start = Instant.now();
            log.info("  Feeding [{}] into Redis (prefix=\"{}\")...", config.filePath(), config.keyPrefix());
            // This is if it's a JSON Array
            if (rootNode.isArray()) {
                for (JsonNode itemNode : rootNode) {
                    String basePath = config.keyPrefix();
                    // If we specified an ID field, append it to the prefix
                    if (config.arrayIdField() != null && itemNode.has(config.arrayIdField())) {
                        basePath += ":" + itemNode.get(config.arrayIdField()).asText();
                    }
                    flattenAndSaveToRedis(itemNode, basePath, count, config.filePath(), start);
                }
            }
            // If the file starts as a JSON Object like relics or TextMaps frfr
            else if (rootNode.isObject()) {
                flattenAndSaveToRedis(rootNode, config.keyPrefix(), count, config.filePath(), start);
            }
            Duration elapsed = Duration.between(start, Instant.now());
            log.info("  Finished feeding [{}]: {} entries written into Redis (took {})",
                    config.filePath(), count[0], formatDuration(elapsed));
        } catch (Exception e) {
            log.error("  Failed to load data from {}: {}", config.filePath(), e.getMessage());
        }
    }

    //This be a recursive function that goes to the end and finds stuff
    private void flattenAndSaveToRedis(JsonNode node, String currentPath, int[] count, String sourceFile, Instant loadStart) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String newPath = currentPath + ":" + field.getKey();
                flattenAndSaveToRedis(field.getValue(), newPath, count, sourceFile, loadStart);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String newPath = currentPath + "[" + i + "]";
                flattenAndSaveToRedis(node.get(i), newPath, count, sourceFile, loadStart);
            }
        } else if (node.isValueNode() && !node.isNull()) {
            redisTemplate.opsForValue().set(currentPath, node.asText());
            count[0]++;
            if (count[0] % 10_000 == 0) {
                Duration elapsed = Duration.between(loadStart, Instant.now());
                log.info("    ... [{}] {} entries written so far (took {})", sourceFile, count[0], formatDuration(elapsed));
            }
        }
    }

    private void processFileIfChanged(FileConfig config, String fileName, String currentHash, String redisHashKey) {

        try {

            String redisHash = (String) redisTemplate
                    .opsForHash()
                    .get(redisHashKey, fileName);

            if (currentHash.equals(redisHash)) {
                log.info("  Skipping [{}]: already loaded (hash unchanged)", fileName);
                return;
            }

            log.info("  [{}] is new or changed (redis hash={}, current hash={}) - loading...", fileName, redisHash, currentHash);

            loadUniversalJsonToRedis(config);

            redisTemplate.opsForHash().put(
                    redisHashKey,
                    fileName,
                    currentHash
            );

            log.info(
                    "  Updated stored version for [{}]",
                    fileName
            );

        } catch (Exception e) {
            log.error(
                    "  Failed processing {}",
                    fileName,
                    e
            );
        }
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = duration.toSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%d.%03ds", seconds, duration.toMillisPart());
        }
    }
}