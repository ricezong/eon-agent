package cn.kong.eon.store.db;

import cn.kong.eon.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 基础设施配置。
 * <p>
 * 职责：建目录 → 创建单例 DataSource（连接复用） → 建表（DDL） → 设置 WAL/FK PRAGMA。
 * 使用 SQLite JDBC 内置的连接池（{@code org.sqlite.SQLiteDataSource}），
 * 避免每次操作都 {@code DriverManager.getConnection()} 创建裸连接。
 */
@Configuration
public class SqliteConfig {

    private static final Logger log = LoggerFactory.getLogger(SqliteConfig.class);

    /**
     * SQLite 单例 DataSource。
     * <p>
     * SQLite 的 {@code SQLiteDataSource} 内部维护连接池，多次调用 {@code getConnection()}
     * 复用同一个文件句柄。WAL 模式与外键约束在首次连接时通过 PRAGMA 设置，
     * 后续从池中取出的连接继承这些设置。
     */
    @Bean
    public DataSource dataSource(AgentConfig config) {
        String dbPath = config.getStorage().getDbPath();

        // 建目录
        try {
            Path path = Path.of(dbPath).toAbsolutePath();
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new RuntimeException("创建数据库目录失败: " + dbPath, e);
        }

        org.sqlite.SQLiteDataSource ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath);

        // 初始化：PRAGMA + DDL
        initSchema(ds);
        log.info("SQLite 已就绪: {}", dbPath);
        return ds;
    }

    /**
     * 建表。在 DataSource 创建后、Bean 注册前执行，保证表结构就绪。
     */
    private void initSchema(DataSource ds) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // WAL 模式：读写并发不互斥
            stmt.execute("PRAGMA journal_mode=WAL");
            // 外键约束
            stmt.execute("PRAGMA foreign_keys=ON");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        session_id          TEXT PRIMARY KEY,
                        user_id             TEXT NOT NULL,
                        title               TEXT,
                        created_at          TEXT NOT NULL,
                        last_active_at      TEXT NOT NULL,
                        message_count       INTEGER NOT NULL DEFAULT 0,
                        user_message_count  INTEGER NOT NULL DEFAULT 0,
                        updated_at          TEXT NOT NULL DEFAULT (datetime('now'))
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_sessions_user
                        ON chat_sessions(user_id, last_active_at DESC)
                    """);
            log.info("SQLite 建表完成");
        } catch (SQLException e) {
            throw new RuntimeException("SQLite 建表失败", e);
        }
    }
}
