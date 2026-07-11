package com.multiregion.platform.failover.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public class JdbcLocalFailoverPromotionGatewayTest {

    private static final AtomicBoolean WRITER_MODE = new AtomicBoolean();

    @BeforeEach
    void resetWriterMode() {
        WRITER_MODE.set(false);
    }

    @Test
    void enablesAndVerifiesWriterModeAgainstTheInjectedLocalDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:local_promotion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS pg_catalog");
        jdbc.execute("DROP ALIAS IF EXISTS pg_catalog.set_writer_mode");
        jdbc.execute("DROP ALIAS IF EXISTS pg_catalog.aurora_is_writer");
        jdbc.execute("DROP ALIAS IF EXISTS pg_catalog.aurora_db_instance_identifier");
        jdbc.execute("CREATE ALIAS pg_catalog.set_writer_mode FOR '"
                + getClass().getName() + ".setWriterMode'");
        jdbc.execute("CREATE ALIAS pg_catalog.aurora_is_writer FOR '"
                + getClass().getName() + ".isWriter'");
        jdbc.execute("CREATE ALIAS pg_catalog.aurora_db_instance_identifier FOR '"
                + getClass().getName() + ".instanceIdentifier'");
        JdbcLocalFailoverPromotionGateway gateway =
                new JdbcLocalFailoverPromotionGateway(dataSource);

        assertThat(gateway.isLocalWriter()).isFalse();

        gateway.enableLocalWriterMode();

        assertThat(gateway.isLocalWriter()).isTrue();
        assertThat(gateway.localWriterId()).isEqualTo("postgres-eu");
    }

    public static boolean setWriterMode(boolean enabled) {
        WRITER_MODE.set(enabled);
        return WRITER_MODE.get();
    }

    public static boolean isWriter() {
        return WRITER_MODE.get();
    }

    public static String instanceIdentifier() {
        return "postgres-eu";
    }
}
