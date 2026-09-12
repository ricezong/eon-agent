package cn.kong.eon.store.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话索引 Repository。以 SQLite 持久化会话索引，替代早期的目录扫描方式。
 */
@Repository
public class SessionIndexRepository {
    private static final Logger log = LoggerFactory.getLogger(SessionIndexRepository.class);

    private final DataSource dataSource;

    public SessionIndexRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record SessionIndex(
            String sessionId,
            String userId,
            String title,
            Instant createdAt,
            Instant lastActiveAt,
            long messageCount,
            long userMessageCount
    ) {}

    public void insert(String sessionId, String userId, String title) {
        String now = Instant.now().toString();
        String sql = "INSERT INTO chat_sessions (session_id, user_id, title, created_at, last_active_at, user_message_count) VALUES (?, ?, ?, ?, ?, 1)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, userId);
            ps.setString(3, title);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("插入会话索引失败: {}", sessionId, e);
        }
    }

    public List<SessionIndex> list(String userId) {
        String sql = "SELECT session_id, user_id, title, created_at, last_active_at, message_count, user_message_count FROM chat_sessions WHERE user_id = ? ORDER BY last_active_at DESC";
        List<SessionIndex> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("列出会话失败: userId={}", userId, e);
        }
        return result;
    }

    public Optional<SessionIndex> find(String userId, String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) return Optional.empty();

        // 先精确匹配
        String exactSql = "SELECT session_id, user_id, title, created_at, last_active_at, message_count, user_message_count FROM chat_sessions WHERE user_id = ? AND session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(exactSql)) {
            ps.setString(1, userId);
            ps.setString(2, idOrPrefix);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("精确查找会话失败: {}", idOrPrefix, e);
        }

        // 前缀匹配
        String prefixSql = "SELECT session_id, user_id, title, created_at, last_active_at, message_count, user_message_count FROM chat_sessions WHERE user_id = ? AND session_id LIKE ?";
        List<SessionIndex> matches = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(prefixSql)) {
            ps.setString(1, userId);
            ps.setString(2, idOrPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("前缀查找会话失败: {}", idOrPrefix, e);
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    public boolean delete(String userId, String sessionId) {
        String sql = "DELETE FROM chat_sessions WHERE user_id = ? AND session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("删除会话索引失败: {}", sessionId, e);
            return false;
        }
    }

    /** 更新活跃时间与账本消息总数。任务结束时调用。 */
    public void touch(String sessionId, int messageCount) {
        String sql = "UPDATE chat_sessions SET last_active_at = ?, message_count = ?, updated_at = datetime('now') WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setInt(2, messageCount);
            ps.setString(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("更新会话索引失败: {}", sessionId, e);
        }
    }

    /** 用户消息数 +1。已有会话收到新用户消息时调用。 */
    public void incrementUserMessageCount(String sessionId) {
        String sql = "UPDATE chat_sessions SET user_message_count = user_message_count + 1, updated_at = datetime('now') WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("递增用户消息数失败: {}", sessionId, e);
        }
    }

    private SessionIndex mapRow(ResultSet rs) throws SQLException {
        return new SessionIndex(
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("title"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("last_active_at")),
                rs.getLong("message_count"),
                rs.getLong("user_message_count")
        );
    }
}
