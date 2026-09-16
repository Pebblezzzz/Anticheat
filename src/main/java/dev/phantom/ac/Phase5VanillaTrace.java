package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;

/**
 * Import/validation contract for observation-only vanilla 1.21.11 captures.
 * This extends the original 46-column Phase5TraceTool format with one
 * explicit missing_fields column so unknown observations are never mistaken
 * for real false/zero values.
 */
public final class Phase5VanillaTrace {
    private Phase5VanillaTrace() {}

    public static final String MAGIC = "# phantom-phase5-trace version=2 protocol=minecraft-java-1.21.11 format=tsv";
    public static final int COLUMN_COUNT = 47;
    public static final String HEADER = "tick\tclient_tick\treceive_nanos\tx\ty\tz\tvx\tvy\tvz\tyaw\tpitch\ton_ground\tforward\tstrafe\tjump\tsprint\tsneak\tpose\tgamemode\tfluid\tsubmerged\tclimbable\tgliding\tbase_movement_speed\tmodifiers\tspeed_amp\tslowness_amp\tjump_boost_amp\tlevitation\tslow_falling\tknockback_x\tknockback_y\tknockback_z\tvelocity_packet\tcorrection_id\tcorrection_pending\tworld_identity\tworld_tick\tcollision\tstep_attempted\tstep_succeeded\tcollision_x\tcollision_y\tcollision_z\tinput_source\tclient_version\tmissing_fields";

    public record Row(String[] columns, Set<String> missingFields) implements Serializable {
        public Row {
            columns = columns.clone();
            if (columns.length != COLUMN_COUNT) {
                throw new IllegalArgumentException("expected " + COLUMN_COUNT + " columns");
            }
            missingFields = Set.copyOf(missingFields);
        }

        public String tick() { return columns[0]; }
        public String clientTick() { return columns[1]; }
        public String receiveNanos() { return columns[2]; }
        public String positionX() { return columns[3]; }
        public String positionY() { return columns[4]; }
        public String positionZ() { return columns[5]; }
        public String velocityX() { return columns[6]; }
        public String velocityY() { return columns[7]; }
        public String velocityZ() { return columns[8]; }
        public String yaw() { return columns[9]; }
        public String pitch() { return columns[10]; }
        public String onGround() { return columns[11]; }
        public String forward() { return columns[12]; }
        public String strafe() { return columns[13]; }
        public String jump() { return columns[14]; }
        public String sprint() { return columns[15]; }
        public String sneak() { return columns[16]; }
        public String pose() { return columns[17]; }
        public String gamemode() { return columns[18]; }
        public String fluid() { return columns[19]; }
        public String submerged() { return columns[20]; }
        public String climbable() { return columns[21]; }
        public String gliding() { return columns[22]; }
        public String baseMovementSpeed() { return columns[23]; }
        public String modifiers() { return columns[24]; }
        public String speedAmp() { return columns[25]; }
        public String slownessAmp() { return columns[26]; }
        public String jumpBoostAmp() { return columns[27]; }
        public String levitation() { return columns[28]; }
        public String slowFalling() { return columns[29]; }
        public String knockbackX() { return columns[30]; }
        public String knockbackY() { return columns[31]; }
        public String knockbackZ() { return columns[32]; }
        public String velocityPacket() { return columns[33]; }
        public String correctionId() { return columns[34]; }
        public String correctionPending() { return columns[35]; }
        public String worldIdentity() { return columns[36]; }
        public String worldTick() { return columns[37]; }
        public String collision() { return columns[38]; }
        public String stepAttempted() { return columns[39]; }
        public String stepSucceeded() { return columns[40]; }
        public String collisionX() { return columns[41]; }
        public String collisionY() { return columns[42]; }
        public String collisionZ() { return columns[43]; }
        public String inputSource() { return columns[44]; }
        public String clientVersion() { return columns[45]; }
    }

    public record Trace(String sourceId, String capturedAtUtc, List<Row> rows) implements Serializable {
        public Trace {
            if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId is required");
            if (capturedAtUtc == null || capturedAtUtc.isBlank()) throw new IllegalArgumentException("capturedAtUtc is required");
            rows = List.copyOf(rows);
        }
    }

    public record Diagnostic(long row, String field, String expected, String actual, String message) implements Serializable {}
    public record ValidationResult(boolean valid, List<Diagnostic> diagnostics) implements Serializable {
        public ValidationResult { diagnostics = List.copyOf(diagnostics); }
    }

    public static Trace read(List<String> lines) {
        if (lines.size() < 4 || !MAGIC.equals(lines.getFirst()) || !HEADER.equals(lines.get(3))) {
            throw new IllegalArgumentException("invalid Phase 5 version 2 trace header");
        }

        String sourceId = metadata(lines.get(1), "source_id");
        String capturedAt = metadata(lines.get(2), "captured_at_utc");
        List<Row> rows = new ArrayList<>();

        long lastTick = -1;
        long lastClientTick = -1;
        long lastReceive = -1;

        for (int i = 4; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith("#")) continue;

            String[] columns = line.split("\\t", -1);
            if (columns.length != COLUMN_COUNT) {
                throw new IllegalArgumentException("invalid trace row " + (i + 1) + ": expected " + COLUMN_COUNT + " columns, got " + columns.length);
            }

            try {
                long tick = Long.parseLong(columns[0]);
                long clientTick = Long.parseLong(columns[1]);
                long receive = Long.parseLong(columns[2]);

                if (tick <= lastTick) throw new IllegalArgumentException("ticks must strictly increase");
                if (clientTick < lastClientTick) throw new IllegalArgumentException("client ticks must not decrease");
                if (receive < lastReceive) throw new IllegalArgumentException("receive_nanos must not decrease");

                validateFinite(columns, 3, 10);
                validateFinite(columns, 23, 23);
                parseBoolean(columns[11]);
                parseBoolean(columns[14]);
                parseBoolean(columns[15]);
                parseBoolean(columns[16]);
                parseBoolean(columns[20]);
                parseBoolean(columns[21]);
                parseBoolean(columns[22]);
                parseBoolean(columns[28]);
                parseBoolean(columns[29]);
                parseBoolean(columns[33]);
                parseBoolean(columns[35]);
                parseBoolean(columns[38]);
                parseBoolean(columns[39]);
                parseBoolean(columns[40]);
                parseBoolean(columns[41]);
                parseBoolean(columns[42]);
                parseBoolean(columns[43]);
                parseInt(columns[12]);
                parseInt(columns[13]);
                parseInt(columns[25]);
                parseInt(columns[26]);
                parseInt(columns[27]);
                parseLong(columns[34]);
                parseLong(columns[37]);

                Set<String> missing = parseMissing(columns[46]);
                rows.add(new Row(columns, missing));
                lastTick = tick;
                lastClientTick = clientTick;
                lastReceive = receive;
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("invalid trace row")) throw e;
                throw new IllegalArgumentException("invalid trace row " + (i + 1) + ": " + Objects.requireNonNullElse(e.getMessage(), "invalid field"), e);
            }
        }

        return new Trace(sourceId, capturedAt, rows);
    }

    public static ValidationResult validate(Trace trace) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        long lastReceive = -1;
        long lastTick = -1;
        long lastClientTick = -1;

        for (Row row : trace.rows()) {
            long tick = parseLong(row.tick());
            long clientTick = parseLong(row.clientTick());
            long receive = parseLong(row.receiveNanos());

            if (tick <= lastTick) diagnostics.add(new Diagnostic(tick, "tick", ">" + lastTick, row.tick(), "server/simulation tick regressed"));
            if (clientTick < lastClientTick) diagnostics.add(new Diagnostic(tick, "client_tick", ">=" + lastClientTick, row.clientTick(), "client tick regressed"));
            if (receive < lastReceive) diagnostics.add(new Diagnostic(tick, "receive_nanos", ">=" + lastReceive, row.receiveNanos(), "capture timing regressed"));
            if (row.clientVersion().isBlank() && !row.missingFields().contains("client_version")) diagnostics.add(new Diagnostic(tick, "client_version", "non-empty", row.clientVersion(), "client version is empty"));
            if (row.worldIdentity().isBlank() && !row.missingFields().contains("world_identity")) diagnostics.add(new Diagnostic(tick, "world_identity", "non-empty", row.worldIdentity(), "world identity is empty"));

            lastTick = tick;
            lastClientTick = clientTick;
            lastReceive = receive;
        }

        return new ValidationResult(diagnostics.isEmpty(), diagnostics);
    }

    /**
     * V2 captures are eligible for the legacy Phase5TraceTool comparator only
     * when every field that comparator needs is actually observed.
     */
    public static boolean fullyObservedForLegacyComparison(Row row) {
        return !row.missingFields().contains("x")
                && !row.missingFields().contains("y")
                && !row.missingFields().contains("z")
                && !row.missingFields().contains("vx")
                && !row.missingFields().contains("vy")
                && !row.missingFields().contains("vz")
                && !row.missingFields().contains("yaw")
                && !row.missingFields().contains("pitch")
                && !row.missingFields().contains("on_ground")
                && !row.missingFields().contains("forward")
                && !row.missingFields().contains("strafe")
                && !row.missingFields().contains("jump");
    }

    private static void validateFinite(String[] c, int from, int to) {
        for (int i = from; i <= to; i++) {
            double value = Double.parseDouble(c[i]);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite numeric value in column " + (i + 1));
        }
    }

    private static boolean parseBoolean(String s) {
        if (!"true".equalsIgnoreCase(s) && !"false".equalsIgnoreCase(s)) throw new IllegalArgumentException("invalid boolean " + s);
        return Boolean.parseBoolean(s);
    }

    private static int parseInt(String s) { return Integer.parseInt(s); }
    private static long parseLong(String s) { return Long.parseLong(s); }

    private static Set<String> parseMissing(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("-")) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String field : raw.split(",", -1)) {
            if (!field.isBlank()) result.add(field);
        }
        return Set.copyOf(result);
    }

    private static String metadata(String line, String key) {
        String prefix = "# " + key + "=";
        if (!line.startsWith(prefix)) throw new IllegalArgumentException("invalid metadata " + key);
        return line.substring(prefix.length());
    }
}
