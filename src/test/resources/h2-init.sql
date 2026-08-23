SET TIME ZONE 'UTC';

CREATE ALIAS IF NOT EXISTS clock_timestamp FOR "java.time.Instant.now";

CREATE ALIAS IF NOT EXISTS similarity AS $$
double similarity(String left, String right) {
    return left != null && right != null && left.equalsIgnoreCase(right) ? 1.0 : 0.0;
}
$$;
