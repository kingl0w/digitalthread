package com.aetnios.dt.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes the three date formats the sources emit to ISO-8601 date strings:
 * seed "2022-06-02", FAA registry "19670128", SDR "1/9/2018 0:00:00".
 * ISO strings sort chronologically, so plain string comparison works in Cypher.
 */
public final class Dates {

    private static final Pattern US = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})");
    private static final Pattern COMPACT = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");

    private Dates() {}

    public static String toIso(String raw) {
        if (raw == null || raw.length() < 8) return null;
        if (raw.matches("^\\d{4}-\\d{2}-\\d{2}.*")) return raw.substring(0, 10);
        Matcher m = COMPACT.matcher(raw);
        if (m.matches()) return m.group(1) + "-" + m.group(2) + "-" + m.group(3);
        m = US.matcher(raw);
        if (m.find()) return String.format("%s-%02d-%02d",
                m.group(3), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        return null;
    }
}
