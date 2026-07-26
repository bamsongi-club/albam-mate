package cloud.bamsongi.albammate.global.time;

import java.util.TimeZone;

public final class UtcTimeZone {

    private static final String TIME_ZONE_ID = "UTC";

    private UtcTimeZone() {}

    public static void configure() {
        System.setProperty("user.timezone", TIME_ZONE_ID);
        TimeZone.setDefault(TimeZone.getTimeZone(TIME_ZONE_ID));
    }
}
