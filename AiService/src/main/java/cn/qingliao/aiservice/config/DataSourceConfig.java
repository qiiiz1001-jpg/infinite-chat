package cn.qingliao.aiservice.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Value("${pgvector.host}")
    private String host;

    @Value("${pgvector.port}")
    private int port;

    @Value("${pgvector.database}")
    private String database;

    @Value("${pgvector.user}")
    private String user;

    @Value("${pgvector.password}")
    private String password;

    @Value("${pgvector.schema:public}")
    private String schema;

    @Bean
    public DataSource pgDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        config.setUsername(user);
        config.setPassword(password);
        if (!schema.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
            throw new IllegalArgumentException("Invalid PostgreSQL schema");
        config.setConnectionInitSql("SET search_path TO " + schema + ",public");
        config.setMaximumPoolSize(5);
        config.setPoolName("bm25-pool");
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource pgDataSource) {
        return new JdbcTemplate(pgDataSource);
    }
}
