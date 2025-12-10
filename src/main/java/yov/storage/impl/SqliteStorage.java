/*
 * This file is part of YourOwnVariables.
 * Copyright (C) 2025 Haizenfell
 *
 * Licensed under the YourOwnVariables Proprietary License.
 * Unauthorized copying, modification, distribution, or reverse engineering
 * of this software is strictly prohibited.
 *
 * Full license text is provided in the LICENSE file.
 */
package yov.storage.impl;

import yov.storage.BatchCapable;
import yov.storage.StorageBackend;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SqliteStorage implements StorageBackend, BatchCapable {

    private final File file;
    private Connection connection;

    private PreparedStatement psSet;
    private PreparedStatement psGet;
    private PreparedStatement psDelete;
    private PreparedStatement psGetAll;

    private boolean inBatch = false;

    public SqliteStorage(File dataFolder) {
        this.file = new File(dataFolder, "variables.db");
    }

    @Override
    public void connect() throws Exception {
        if (connection != null && !connection.isClosed()) return;

        File parent = file.getParentFile();
        if (!parent.exists()) parent.mkdirs();

        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());

        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
        }

        createTable();
        prepareStatements();
    }

    private void ensureConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("SQLite connection is closed");
        }
    }

    private void createTable() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS variables (
                    key   TEXT PRIMARY KEY,
                    value TEXT
                );
                """;
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private void prepareStatements() throws SQLException {
        psSet = connection.prepareStatement(
                "INSERT INTO variables (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
        );
        psGet = connection.prepareStatement(
                "SELECT value FROM variables WHERE key = ?"
        );
        psDelete = connection.prepareStatement(
                "DELETE FROM variables WHERE key = ?"
        );
        psGetAll = connection.prepareStatement(
                "SELECT key FROM variables"
        );
    }

    @Override
    public void set(String key, String value) throws Exception {
        ensureConnection();
        psSet.setString(1, key);
        psSet.setString(2, value);
        psSet.executeUpdate();
    }

    @Override
    public String get(String key) throws Exception {
        ensureConnection();
        psGet.setString(1, key);
        try (ResultSet rs = psGet.executeQuery()) {
            return rs.next() ? rs.getString("value") : null;
        }
    }

    @Override
    public void delete(String key) throws Exception {
        ensureConnection();
        psDelete.setString(1, key);
        psDelete.executeUpdate();
    }

    @Override
    public List<String> getAllKeys() throws Exception {
        ensureConnection();
        List<String> list = new ArrayList<>();
        try (ResultSet rs = psGetAll.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getString("key"));
            }
        }
        return list;
    }

    @Override
    public List<String> getKeysByPrefix(String prefix) throws Exception {
        ensureConnection();
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT key FROM variables WHERE key LIKE ?"
        )) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString("key"));
                }
            }
        }
        return list;
    }

    @Override
    public void beginBatch() throws Exception {
        ensureConnection();
        if (!inBatch) {
            connection.setAutoCommit(false);
            inBatch = true;
        }
    }

    @Override
    public Connection getConnection() throws Exception {
        ensureConnection();
        return connection;
    }

    @Override
    public void endBatch() throws Exception {
        ensureConnection();
        if (inBatch) {
            connection.commit();
            connection.setAutoCommit(true);
            inBatch = false;
        }
    }

    @Override
    public void close() throws Exception {
        if (psSet != null) psSet.close();
        if (psGet != null) psGet.close();
        if (psDelete != null) psDelete.close();
        if (psGetAll != null) psGetAll.close();

        if (connection != null && !connection.isClosed()) {
            if (inBatch) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {}
            }
            connection.close();
        }
    }
}
