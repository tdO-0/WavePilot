package org.example.wavepilot.experiment.repository.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.example.wavepilot.experiment.outbox.mysql.OutboxEventMapper;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wavepilot.job-repository", havingValue = "mysql")
@MapperScans({
        @MapperScan(basePackageClasses = ExperimentJobMapper.class),
        @MapperScan(basePackageClasses = OutboxEventMapper.class)
})
@EnableTransactionManagement
public class MySqlJobConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(Environment env) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
        config.setUsername(env.getRequiredProperty("spring.datasource.username"));
        config.setPassword(env.getRequiredProperty("spring.datasource.password"));
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    @Bean(initMethod = "migrate")
    public Flyway backendFlyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
    }

    /**
     * MyBatis-Plus statements join Spring-managed transactions through this manager, so
     * {@code @Transactional} boundaries (outbox enqueue, lease requeue) commit or roll
     * back job state and outbox events together.
     */
    @Bean
    public PlatformTransactionManager backendTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
