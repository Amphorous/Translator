package org.hoyo.translator.loading;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds a "_warning" entry to translate responses while data is loading (or hasn't
 * finished loading for the first time yet), so callers know the data they received
 * might be stale or incomplete.
 */
public final class LoadingWarningUtil {

    private static final String WARNING_KEY = "_warning";
    private static final String WARNING_MESSAGE =
            "The translator service is loading data, you might be seeing old/broken info right now.";

    private LoadingWarningUtil() {
    }

    public static Map<String, String> withLoadingWarning(Map<String, String> data, DataLoadingStatus status) {
        if (!status.isLoading() && status.hasCompletedAtLeastOnce()) {
            return data;
        }

        Map<String, String> result = new LinkedHashMap<>(data);
        result.put(WARNING_KEY, WARNING_MESSAGE);
        return result;
    }
}
