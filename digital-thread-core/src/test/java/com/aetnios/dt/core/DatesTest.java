package com.aetnios.dt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** One check per source date format the normalizer claims to handle. */
class DatesTest {

    @Test
    void normalizesAllThreeSourceFormats() {
        assertEquals("2022-06-02", Dates.toIso("2022-06-02"));            // seed
        assertEquals("2022-06-02", Dates.toIso("2022-06-02T10:00:00"));   // ISO with time
        assertEquals("1967-01-28", Dates.toIso("19670128"));              // FAA registry
        assertEquals("2018-01-09", Dates.toIso("1/9/2018 0:00:00"));      // SDR
        assertNull(Dates.toIso(""));
        assertNull(Dates.toIso(null));
        assertNull(Dates.toIso("not a date"));
    }
}
