package io.cafeai.support;

import io.cafeai.core.CafeAI;
import io.cafeai.rag.Source;

/**
 * Seeds the Helios knowledge base at startup.
 *
 * In production this would load from real documentation files,
 * a GitHub repository, or a documentation site. For this example
 * we use inline text that mirrors realistic library docs.
 */
public class HeliosKnowledgeBase {

    public static void seed(CafeAI app) {

        // ── Configuration ─────────────────────────────────────
        app.ingest(Source.text("""
            # Helios Configuration
            
            Helios is configured via the HeliosPool.builder() fluent API.
            
            Basic setup:
            
                HeliosPool pool = HeliosPool.builder()
                    .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
                    .username("app_user")
                    .password("secret")
                    .maxPoolSize(20)
                    .minIdle(5)
                    .connectionTimeout(30_000)
                    .idleTimeout(600_000)
                    .build();
            
            Key configuration properties:
            - maxPoolSize: Maximum number of connections in the pool (default: 10)
            - minIdle: Minimum idle connections maintained (default: 0)
            - connectionTimeout: Milliseconds to wait for a connection (default: 30000)
            - idleTimeout: Milliseconds before idle connections are removed (default: 600000)
            - maxLifetime: Maximum lifetime of a connection in milliseconds (default: 1800000)
            - keepaliveTime: Interval for keepalive queries on idle connections (default: 0, disabled)
            """, "helios/configuration"));

        // ── Getting Started ───────────────────────────────────
        app.ingest(Source.text("""
            # Getting Started with Helios
            
            Add Helios to your project:
            
                // Gradle
                implementation 'io.helios:helios-core:2.1.0'
                
                // Maven
                <dependency>
                    <groupId>io.helios</groupId>
                    <artifactId>helios-core</artifactId>
                    <version>2.1.0</version>
                </dependency>
            
            Minimum Java version: Java 17.
            Helios supports JDBC 4.2 compliant drivers.
            Tested with PostgreSQL, MySQL, MariaDB, and H2.
            
            First connection:
            
                HeliosPool pool = HeliosPool.builder()
                    .jdbcUrl("jdbc:postgresql://localhost/mydb")
                    .username("user")
                    .password("pass")
                    .build();
                
                try (Connection conn = pool.getConnection()) {
                    // use connection
                }
            
            Always close connections — Helios returns them to the pool.
            Use try-with-resources to guarantee this.
            """, "helios/getting-started"));

        // ── Connection Lifecycle ──────────────────────────────
        app.ingest(Source.text("""
            # Connection Lifecycle in Helios
            
            Helios manages connections through a defined lifecycle:
            
            1. IDLE — connection is in the pool, ready for use
            2. IN_USE — connection has been acquired by application code
            3. VALIDATION — connection is being tested before lending
            4. CLOSING — connection is being removed from the pool
            
            Validation:
            Helios validates connections before lending using a configurable
            test query. Set via .connectionTestQuery("SELECT 1").
            For databases that support JDBC isValid(), no test query is needed.
            
            Health checks:
            
                pool.getHealthStatus()  // returns HeliosHealthStatus
                pool.getActiveCount()   // connections currently in use
                pool.getIdleCount()     // connections waiting in pool
                pool.getTotalCount()    // total connections managed
            
            Eviction:
            Connections exceeding maxLifetime are evicted and replaced.
            Connections idle longer than idleTimeout are removed if pool
            size exceeds minIdle.
            """, "helios/connection-lifecycle"));

        // ── Common Errors ─────────────────────────────────────
        app.ingest(Source.text("""
            # Common Helios Errors and Solutions
            
            HeliosTimeoutException: Connection acquisition timed out
            - Cause: All connections are in use and pool is at maxPoolSize
            - Fix 1: Increase maxPoolSize
            - Fix 2: Increase connectionTimeout to wait longer
            - Fix 3: Audit code for connections not being closed
            - Fix 4: Use pool.getActiveCount() to diagnose saturation
            
            HeliosConnectionException: Unable to acquire JDBC connection
            - Cause: Database is unreachable or credentials are wrong
            - Fix: Verify jdbcUrl, username, password, and network access
            
            HeliosConfigurationException: Invalid pool configuration
            - Cause: maxPoolSize < minIdle, or negative timeout values
            - Fix: Ensure maxPoolSize >= minIdle and all timeouts > 0
            
            Connection leak detection:
            Enable with .leakDetectionThreshold(2000) — logs a warning
            if a connection is held for more than 2000ms without release.
            """, "helios/errors"));

        // ── Spring Boot Integration ───────────────────────────
        app.ingest(Source.text("""
            # Helios with Spring Boot
            
            Helios integrates with Spring Boot as a DataSource:
            
                @Configuration
                public class DataSourceConfig {
                
                    @Bean
                    public DataSource dataSource() {
                        return HeliosPool.builder()
                            .jdbcUrl(env.getProperty("spring.datasource.url"))
                            .username(env.getProperty("spring.datasource.username"))
                            .password(env.getProperty("spring.datasource.password"))
                            .maxPoolSize(20)
                            .build();
                    }
                }
            
            Or use Spring Boot autoconfiguration by setting:
            
                spring.datasource.type=io.helios.HeliosPool
            
            Helios metrics integrate with Spring Boot Actuator automatically
            when helios-actuator is on the classpath.
            """, "helios/spring-boot"));

        // ── GitHub Issues (live data comes via tool in Phase 5) ──
        app.ingest(Source.text("""
            # Known Issues and Recent Fixes
            
            Issue #142 — Connection leak under high concurrency (FIXED in 2.1.0)
            Fixed by improving lock contention in the connection acquisition path.
            Upgrade to 2.1.0 or later if experiencing this.
            
            Issue #156 — maxLifetime not honoured when database restarts (OPEN)
            Workaround: set keepaliveTime to 30000 to detect stale connections.
            
            Issue #171 — NullPointerException when jdbcUrl contains special characters (FIXED in 2.0.8)
            Fixed by properly encoding the URL before passing to the JDBC driver.
            
            Issue #189 — leakDetectionThreshold fires incorrectly for long transactions (OPEN)
            Workaround: set leakDetectionThreshold higher than your longest transaction.
            """, "helios/known-issues"));

        System.out.println("✓ Helios knowledge base loaded (" +
            6 + " documents ingested)");
    }
}
