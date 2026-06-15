package org.hoyo.translator.loading;

import lombok.RequiredArgsConstructor;
import org.hoyo.translator.RedisDataLoaderService;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/data")
@RequiredArgsConstructor
public class DataController {

    private final RedisDataLoaderService redisDataLoaderService;
    private final DataLoadingStatus loadingStatus;
    private final TaskExecutor dataLoaderExecutor;

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        if (loadingStatus.isLoading()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(statusBody());
        }

        dataLoaderExecutor.execute(redisDataLoaderService::refreshData);
        return ResponseEntity.accepted().body(statusBody());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(statusBody());
    }

    private Map<String, Object> statusBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("loading", loadingStatus.isLoading());
        body.put("lastStarted", loadingStatus.getLastStarted());
        body.put("lastCompleted", loadingStatus.getLastCompleted());
        body.put("lastError", loadingStatus.getLastError());
        return body;
    }
}
