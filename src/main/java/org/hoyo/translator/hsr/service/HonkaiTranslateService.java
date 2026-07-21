package org.hoyo.translator.hsr.service;

import org.hoyo.translator.loading.DataLoadingStatus;
import org.hoyo.translator.loading.LoadingWarningUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HonkaiTranslateService {

    public static final int MAX_BATCH_SIZE = 200;

    private final StringRedisTemplate redisTemplate;
    private final DataLoadingStatus loadingStatus;

    public HonkaiTranslateService(StringRedisTemplate redisTemplate, DataLoadingStatus loadingStatus) {
        this.redisTemplate = redisTemplate;
        this.loadingStatus = loadingStatus;
    }

    private String resolveLanguage(String language) {
        switch (language) {
            case "cn": language = "chs"; break;
            case "tw": language = "cht"; break;
        }
        return language.toUpperCase();
    }

    // hsr.json (loaded into Redis under the "hsr" prefix) keys its locales differently
    // from the textMap system above - zh-cn/zh-tw/ja/ko instead of cn/tw/jp/kr - so this
    // needs its own mapping rather than reusing resolveLanguage.
    private String resolveAssetLocale(String language) {
        return switch (language) {
            case "cn" -> "zh-cn";
            case "tw" -> "zh-tw";
            case "jp" -> "ja";
            case "kr" -> "ko";
            default -> language;
        };
    }

    // hsr.json mixes stat names in with unrelated glossary entries ("trailblazer",
    // "su", ...) and thousands of numeric-hash-keyed character/item name strings under
    // the same locale object. Stat keys are always PascalCase identifiers (BaseHP,
    // CriticalChance, ElationDamageAddedRatio, ...), which cleanly distinguishes them
    // from both without needing to hardcode/maintain the ~56-key list by hand.
    private static final Pattern STAT_KEY_PATTERN = Pattern.compile("^[A-Z][A-Za-z]*$");

    public Map<String, String> getStatNames(String language) {
        String locale = resolveAssetLocale(language);
        String prefix = "hsr:" + locale + ":";

        Set<String> keys = redisTemplate.keys(prefix + "*");
        Map<String, String> result = new LinkedHashMap<>();

        if (keys != null) {
            for (String key : keys) {
                String statKey = key.substring(prefix.length());
                if (!STAT_KEY_PATTERN.matcher(statKey).matches()) continue;

                String value = redisTemplate.opsForValue().get(key);
                if (value != null) {
                    result.put(statKey, value);
                }
            }
        }

        return LoadingWarningUtil.withLoadingWarning(result, loadingStatus);
    }

    public Map<String, String> translateHash(String language, String hash) {
        return translateHashes(language, List.of(hash));
    }

    // resolves any textmap hashes (avatar names, relic names, ...) to translated
    // strings in one Redis round trip; response maps hash -> translation
    public Map<String, String> translateHashes(String language, List<String> hashes) {
        String lang = resolveLanguage(language);

        List<String> distinctHashes = hashes.stream().distinct().toList();
        List<String> keys = distinctHashes.stream()
                .map(hash -> "textMap" + lang + ":" + hash)
                .toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);

        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < distinctHashes.size(); i++) {
            String value = values != null ? values.get(i) : null;
            result.put(distinctHashes.get(i), value != null ? value : "Translation Missing");
        }

        return LoadingWarningUtil.withLoadingWarning(result, loadingStatus);
    }

    public Map<String, String> translateRelicInfo(String language, String tid) {

        // frontend sends ["en", "cn", "tw", "de", "es", "fr", "id", "jp", "kr", "pt", "ru", "th", "vi"]
        // backend translates it to ["EN", "CHS", "CHT", "DE", "ES", "FR", "ID", "JP", "KR", "PT", "RU", "TH", "VI"]
        language = resolveLanguage(language);

        String artifactHashKey = "relic_config:" + tid + ":ItemName:Hash";
        String artifactHash = redisTemplate.opsForValue().get(artifactHashKey);

        String artifactName = "Unknown";
        if (artifactHash != null) {
            artifactName = redisTemplate.opsForValue().get("textMap" + language + ":" + artifactHash);
        }

        // drop first and last digits
        int length = tid.length();
        String setId = tid.substring(1, length - 1);

        String setHashKey = "relics:Sets:" + setId + ":Name";
        String setHash = redisTemplate.opsForValue().get(setHashKey);

        String setName = "Unknown";
        if (setHash != null) {
            setName = redisTemplate.opsForValue().get("textMap" + language + ":" + setHash);
        }

        Map<String, String> result = Map.of(
                "ArtifactName", artifactName != null ? artifactName : "Translation Missing",
                "SetName", setName != null ? setName : "Translation Missing"
        );

        return LoadingWarningUtil.withLoadingWarning(result, loadingStatus);
    }

    public Map<String, Object> getRelicCatalog(String language) {
        language = resolveLanguage(language);

        List<Map<String, String>> sets = new ArrayList<>();
        List<Map<String, String>> relics = new ArrayList<>();

        // Scan all set keys: relics:Sets:{setId}:Name
        Set<String> setKeys = redisTemplate.keys("relics:Sets:*:Name");
        Pattern setPattern = Pattern.compile("relics:Sets:(\\d+):Name");

        if (setKeys != null) {
            for (String key : setKeys) {
                Matcher m = setPattern.matcher(key);
                if (!m.matches()) continue;
                String setId = m.group(1);
                String hash = redisTemplate.opsForValue().get(key);
                if (hash == null) continue;
                String name = redisTemplate.opsForValue().get("textMap" + language + ":" + hash);
                if (name == null) continue;
                sets.add(Map.of("setId", setId, "name", name));
            }
        }

        sets.sort(Comparator.comparing(s -> s.get("name")));

        // Scan all relic keys: relic_config:{tid}:ItemName:Hash
        Set<String> relicKeys = redisTemplate.keys("relic_config:*:ItemName:Hash");
        Pattern relicPattern = Pattern.compile("relic_config:(\\d+):ItemName:Hash");

        if (relicKeys != null) {
            for (String key : relicKeys) {
                Matcher m = relicPattern.matcher(key);
                if (!m.matches()) continue;
                String tid = m.group(1);
                String hash = redisTemplate.opsForValue().get(key);
                if (hash == null) continue;
                String name = redisTemplate.opsForValue().get("textMap" + language + ":" + hash);
                if (name == null) continue;
                String setId = tid.substring(1, tid.length() - 1);
                relics.add(Map.of("tid", tid, "name", name, "setId", setId));
            }
        }

        relics.sort(Comparator.comparing(r -> r.get("name")));

        return Map.of("sets", sets, "relics", relics);
    }

    // Same scan-by-regex-then-resolve-hash approach as getRelicCatalog above,
    // against avatar_config:{avatarId}:AvatarName:Hash (loaded from avatars.json
    // by RedisDataLoaderService, same object-keyed-by-id shape as relics.json).
    // Lets the frontend fuzzy-search characters by their localised name and
    // resolve straight to an avatarId, without the user needing to already
    // have a build record for that character loaded first.
    public Map<String, Object> getAvatarCatalog(String language) {
        language = resolveLanguage(language);

        List<Map<String, String>> avatars = new ArrayList<>();

        Set<String> avatarKeys = redisTemplate.keys("avatar_config:*:AvatarName:Hash");
        Pattern avatarPattern = Pattern.compile("avatar_config:(\\d+):AvatarName:Hash");

        if (avatarKeys != null) {
            for (String key : avatarKeys) {
                Matcher m = avatarPattern.matcher(key);
                if (!m.matches()) continue;
                String avatarId = m.group(1);
                String hash = redisTemplate.opsForValue().get(key);
                if (hash == null) continue;
                String name = redisTemplate.opsForValue().get("textMap" + language + ":" + hash);
                if (name == null) continue;
                avatars.add(Map.of("avatarId", avatarId, "name", name));
            }
        }

        avatars.sort(Comparator.comparing(a -> a.get("name")));

        return Map.of("avatars", avatars);
    }
}
