package org.hoyo.translator.hsr.service;

import org.hoyo.translator.loading.DataLoadingStatus;
import org.hoyo.translator.loading.LoadingWarningUtil;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

@Service
public class HonkaiTranslateService {

    private final StringRedisTemplate redisTemplate;
    private final DataLoadingStatus loadingStatus;

    public HonkaiTranslateService(StringRedisTemplate redisTemplate, DataLoadingStatus loadingStatus) {
        this.redisTemplate = redisTemplate;
        this.loadingStatus = loadingStatus;
    }

    public Map<String, String> translateRelicInfo(String language, String tid) {

        // frontend sends ["en", "cn", "tw", "de", "es", "fr", "id", "jp", "kr", "pt", "ru", "th", "vi"]
        // backend translates it to ["EN", "CHS", "CHT", "DE", "ES", "FR", "ID", "JP", "KR", "PT", "RU", "TH", "VI"]
        switch (language) {
            case "cn": language = "chs";
            case "tw": language = "cht";
        }

        language = language.toUpperCase();
        String artifactHashKey = "relic_config:" + tid + ":ItemName:Hash";
        String artifactHash = (String) redisTemplate.opsForValue().get(artifactHashKey);

        String artifactName = "Unknown";
        if (artifactHash != null) {
            artifactName = (String) redisTemplate.opsForValue().get("textMap"+language+":" + artifactHash);
        }

        // drop first and last digits
        int length = tid.length();
        String setId = tid.substring(1, length-1);

        String setHashKey = "relics:Sets:" + setId + ":Name";
        String setHash = (String) redisTemplate.opsForValue().get(setHashKey);

        String setName = "Unknown";
        if (setHash != null) {
            setName = (String) redisTemplate.opsForValue().get("textMap"+language+":" + setHash);
        }

        Map<String, String> result = Map.of(
                "ArtifactName", artifactName != null ? artifactName : "Translation Missing",
                "SetName", setName != null ? setName : "Translation Missing"
        );

        return LoadingWarningUtil.withLoadingWarning(result, loadingStatus);
        //input as TID (5 digit number),language(currently only EN), which is found in itemConfigRelic, find it go in itemName.Hash,
        // use that to get it from TextMapEN, output is artifact name and set name
        //to get setname, take the middle three numbers of TID, go to relics.set.TID.name gives a hash which again is located from TextMapEN
    }

}
