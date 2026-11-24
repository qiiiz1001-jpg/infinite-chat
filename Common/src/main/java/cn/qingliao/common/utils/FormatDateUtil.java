package cn.qingliao.common.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

public final class FormatDateUtil {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private FormatDateUtil() {}

    public static String formatDate(Date date) {
        return FORMAT.format(date.toInstant().atZone(ZONE));
    }

    public static long parseDateTimeToMillis(String value) {
        return LocalDateTime.parse(value, FORMAT).atZone(ZONE).toInstant().toEpochMilli();
    }
}
