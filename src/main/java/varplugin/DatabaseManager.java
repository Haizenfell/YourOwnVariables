package varplugin;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private Connection connection;
    private final File dbFile;

    public DatabaseManager(File dataFolder) {
        this.dbFile = new File(dataFolder, "variables.db");
    }

    public synchronized void connect() throws SQLException {
        if (connection != null && !connection.isClosed()) return;

        File parent = dbFile.getParentFile();
        if (!parent.exists()) parent.mkdirs();

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver not found!", e);
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        createTable();
    }


    private void createTable() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS variables (
                        key TEXT PRIMARY KEY,
                        value TEXT
                    );
                    """);
        }
    }

    private void ensureConnection() throws SQLException {
        if (connection == null || connection.isClosed()) connect();
    }

    public void setVariable(String key, String value) throws SQLException {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO variables (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
        )) {
            ps.setString(1, key.toLowerCase());
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public String getVariable(String key) throws SQLException {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM variables WHERE key = ?"
        )) {
            ps.setString(1, key.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        }
        return null;
    }

    public void deleteVariable(String key) throws SQLException {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM variables WHERE key = ?"
        )) {
            ps.setString(1, key.toLowerCase());
            ps.executeUpdate();
        }
    }

    public List<String> getAllKeys() throws SQLException {
        ensureConnection();
        List<String> keys = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT key FROM variables")) {
            while (rs.next()) {
                keys.add(rs.getString("key"));
            }
        }
        return keys;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) connection.close();
    }
}
