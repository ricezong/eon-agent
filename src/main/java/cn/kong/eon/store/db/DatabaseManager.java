package cn.kong.eon.store.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 数据库管理。负责连接、建表、WAL 模式开启。
 */
@Component
public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final String dbPath;

    public DatabaseManager(@Value("${eon.storage.db_path:/home/workspace/sessions/eon.db}") String dbPath) {
        this.dbPath = dbPath;
        try {
            Path path = Path.of(dbPath).toAbsolutePath();
            Files.createDirectories(path.getParent());
        } catch (Exception e) {
            throw new RuntimeException("创建数据库目录失败: " + dbPath, e);
        }
        initSchema();
        log.info("SQLite 已就绪: {}", dbPath);
    }

    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }
        return conn;
    }

    private void initSchema() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
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
