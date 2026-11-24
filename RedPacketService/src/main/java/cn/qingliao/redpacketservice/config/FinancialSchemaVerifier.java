package cn.qingliao.redpacketservice.config;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;

import javax.sql.DataSource;

/** 启动时验证账务幂等所依赖的唯一索引，避免漏执行升级 SQL 后带病消费。 */
@Component
@RequiredArgsConstructor
public class FinancialSchemaVerifier implements InitializingBean {
    private final DataSource dataSource;

    @Override
    public void afterPropertiesSet() throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet indexes =
                        connection
                                .getMetaData()
                                .getIndexInfo(
                                        connection.getCatalog(),
                                        null,
                                        "red_packet_receive",
                                        true,
                                        false)) {
            Map<String, Set<String>> columns = new HashMap<>();
            while (indexes.next()) {
                String index = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                if (index != null && column != null)
                    columns.computeIfAbsent(index, key -> new HashSet<>())
                            .add(column.toLowerCase(Locale.ROOT));
            }
            if (columns.values().stream()
                    .noneMatch(value -> value.equals(Set.of("red_packet_id", "receiver_id"))))
                throw new IllegalStateException("缺少红包领取唯一约束，请先执行 database/02-business-upgrade.sql");
        }
    }
}
