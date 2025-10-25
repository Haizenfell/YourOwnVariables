package varplugin;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StorageManager {
    private Connection connection;
    private final String type;
    private final File dbFile;

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;

    private final boolean autoReconnect;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public StorageManager(File dataFolder, String type,
                          String host, int port, String database, String username, String password,
                          boolean autoReconnect) {
        this.type = type.toLowerCase();
        this.dbFile = new File(dataFolder, "variables.db");
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.autoReconnect = autoReconnect;
    }

    public synchronized void connect() throws SQLException {
        if (connection != null && !connection.isClosed()) return;

        try {
            if (type.equals("mysql")) {
                Class.forName("com.mysql.cj.jdbc.Driver");
                String url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                        "?useSSL=false&autoReconnect=" + autoReconnect + "&characterEncoding=utf8";
                connection = DriverManager.getConnection(url, username, password);
            } else {
                File parent = dbFile.getParentFile();
                if (!parent.exists()) parent.mkdirs();
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            }
        } catch (ClassNotFoundException e) {
            throw new SQLException("Database driver not found!", e);
        }

        createTable();
    }

    private void createTable() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS variables (
                    `key` VARCHAR(255) PRIMARY KEY,
                    `value` TEXT
                );
                """;
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    private void ensureConnection() throws SQLException {
        try {
            if (connection == null || connection.isClosed() || !connection.isValid(2)) {
                if (autoReconnect) {
                    connect();
                } else {
                    throw new SQLException("Connection lost and autoReconnect is disabled.");
                }
            }
        } catch (SQLException e) {
            if (autoReconnect) connect();
            else throw e;
        }
    }

    // --------------------- CRUD ----------------------

    public void setVariable(String key, String value) throws SQLException {
        ensureConnection();
        cache.put(key, value); // кеш всегда актуален
        String sql = type.equals("mysql")
                ? "INSERT INTO variables (`key`, `value`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)"
                : "INSERT INTO variables (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key.toLowerCase());
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public String getVariable(String key) throws SQLException {
        // если есть в кеше — сразу вернуть
        String cached = cache.get(key);
        if (cached != null) return cached;

        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM variables WHERE `key` = ?")) {
            ps.setString(1, key.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString("value");
                    cache.putIfAbsent(key, val); // если в кеше нет — добавляем
                    return val;
                }
            }
        }
        return null;
    }

    public void deleteVariable(String key) throws SQLException {
        ensureConnection();
        cache.remove(key);
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM variables WHERE `key` = ?")) {
            ps.setString(1, key.toLowerCase());
            ps.executeUpdate();
        }
    }

    public List<String> getAllKeys() throws SQLException {
        ensureConnection();
        List<String> keys = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT `key` FROM variables")) {
            while (rs.next()) {
                String k = rs.getString("key");
                keys.add(k);
                cache.putIfAbsent(k, getVariable(k)); // заполняем кеш если нужно
            }
        }
        return keys;
    }

    public Map<String, String> getCache() {
        return cache;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) connection.close();
    }
}
