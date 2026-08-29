package org.example.algorithmdebug.harness;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 对 stdout/stderr 分别做跨字节块匹配，避免把两个流拼接成伪标记。 */
final class OutputMarkerRegistry {
    private final Map<String, MarkerState> markers;

    OutputMarkerRegistry(List<String> requested) {
        if (requested == null || requested.size() > 32) {
            throw new IllegalArgumentException("The output marker count must be between 0 and 32");
        }
        LinkedHashMap<String, MarkerState> checked = new LinkedHashMap<>();
        for (String marker : requested) {
            if (marker == null || marker.isBlank() || marker.length() > 1_024
                    || checked.containsKey(marker)) {
                throw new IllegalArgumentException("Output markers are invalid or duplicated");
            }
            checked.put(marker, new MarkerState(marker.getBytes(StandardCharsets.UTF_8)));
        }
        markers = Map.copyOf(checked);
    }

    OutputChunkObserver observer(int streamIndex) {
        if (streamIndex < 0 || streamIndex > 1) {
            throw new IllegalArgumentException("streamIndex must be 0 or 1");
        }
        return (bytes, offset, length) -> markers.values().forEach(
                marker -> marker.accept(streamIndex, bytes, offset, length));
    }

    CompletableFuture<Void> future(String marker) {
        MarkerState state = markers.get(marker);
        if (state == null) {
            throw new IllegalArgumentException("A pending output marker was not registered at launch");
        }
        return state.observed;
    }

    private static final class MarkerState {
        private final byte[] pattern;
        private final int[] prefix;
        private final int[] positions = new int[2];
        private final CompletableFuture<Void> observed = new CompletableFuture<>();

        private MarkerState(byte[] pattern) {
            this.pattern = pattern;
            this.prefix = prefix(pattern);
        }

        private synchronized void accept(int stream, byte[] bytes, int offset, int length) {
            if (observed.isDone()) {
                return;
            }
            int matched = positions[stream];
            for (int index = offset; index < offset + length; index++) {
                while (matched > 0 && bytes[index] != pattern[matched]) {
                    matched = prefix[matched - 1];
                }
                if (bytes[index] == pattern[matched]) {
                    matched++;
                    if (matched == pattern.length) {
                        positions[stream] = matched;
                        observed.complete(null);
                        return;
                    }
                }
            }
            positions[stream] = matched;
        }

        private static int[] prefix(byte[] pattern) {
            int[] result = new int[pattern.length];
            for (int index = 1, matched = 0; index < pattern.length; index++) {
                while (matched > 0 && pattern[index] != pattern[matched]) {
                    matched = result[matched - 1];
                }
                if (pattern[index] == pattern[matched]) {
                    matched++;
                }
                result[index] = matched;
            }
            return result;
        }
    }
}
