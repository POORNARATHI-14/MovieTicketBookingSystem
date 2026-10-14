package com.movieticket.events;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tiny helper to build event payload maps (null-safe, keeps insertion order). */
public final class EventData {

    private EventData() {
    }

    /** EventData.of("bookingId", 5, "seatId", 9) */
    public static Map<String, Object> of(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
