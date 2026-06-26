package org.hoyo.translator.loading;

import lombok.RequiredArgsConstructor;
import org.hoyo.translator.RedisDataLoaderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.hsr.checkForUpdate", havingValue = "true")
public class DataRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataRefreshScheduler.class);

    private final RedisDataLoaderService redisDataLoaderService;

    @Scheduled(cron = "${translator.data.refresh-cron:0 0 3 */3 * ?}")
    public void checkForUpdate() {
        log.info("Scheduled data refresh triggered.");
        redisDataLoaderService.refreshData();
    }
}
