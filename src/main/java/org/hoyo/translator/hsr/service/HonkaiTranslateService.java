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
}
