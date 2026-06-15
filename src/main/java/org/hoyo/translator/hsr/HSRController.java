package org.hoyo.translator.hsr;

import lombok.RequiredArgsConstructor;
import org.hoyo.translator.hsr.service.HonkaiTranslateService;
import org.hoyo.translator.loading.DataPaths;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

//@CrossOrigin
@RestController
@RequestMapping("/hsr")
@RequiredArgsConstructor
public class HSRController {

    private final HonkaiTranslateService honkaiTranslateService;
    private final DataPaths dataPaths;

    @GetMapping("/relic-info/{language}/{tid}")
    public ResponseEntity<Map<String, String>> getRelicInfo(@PathVariable String language, @PathVariable String tid) {
        if (tid == null) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(honkaiTranslateService.translateRelicInfo(language, tid));
    }

    @GetMapping("/localization/getlist")
    public ResponseEntity<List<Map<String, Boolean>>> getLocalizationList() {
        Map<String, String> metadata = dataPaths.readTextMapMetadata();

        Set<String> languages = metadata.keySet()
                .stream()
                .map(name -> name.replace(".json",""))
                .map(name -> name.replaceFirst("^TextMap",""))
                .map(name -> name.replaceAll("_[0-9]+$",""))
                .map(lang -> switch(lang){
                    case "CHS" -> "cn";
                    case "CHT" -> "tw";
                    default -> lang.toLowerCase();
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return ResponseEntity.ok(languages.stream().map(lang->Map.of(lang,true)).toList());
    }

}
