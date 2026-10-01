package com.cagritasoz.user_service.testsupport;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

// Tests use Instant for every timestamp, but neither the Postgres JDBC driver nor JdbcTemplate can bind
// an Instant as a parameter (JDBC maps timestamptz to OffsetDateTime). Pass parameters through here and
// each Instant is converted at the last moment; everything else is passed on unchanged.
public final class JdbcParameters {

    private JdbcParameters() {
    }

    public static Object[] bindable(Object... values) {

        return Arrays.stream(values)
                .map(value -> value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value) // Convert to OffsetDateTime
                .toArray();

    }
}
