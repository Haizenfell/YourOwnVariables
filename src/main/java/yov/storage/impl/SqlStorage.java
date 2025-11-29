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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import yov.storage.StorageBackend;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SqlStorage implements StorageBackend {

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String pass;

    private HikariDataSource dataSource;

    public SqlStorage(String backend,
                      String host,
                      int port,
                      String database,
                      String user,
                      String pass) {

        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.pass = pass;
    }

    @Override
    public void connect() throws Exception {

        Class.forName("org.mariadb.jdbc.Driver");

        String jdbcUrl =
                "jdbc:mariadb://" + host + ":" + port + "/" + database +
                        "?useUnicode=true&characterEncoding=utf8mb4&sessionVariables=sql_mode=''";

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(pass);

        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5000);
        cfg.setIdleTimeout(60000);
        cfg.setMaxLifetime(30 * 60 * 1000L);

        cfg.setPoolName("YOV-MARIADB");
        cfg.setInitializationFailTimeout(-1);
        cfg.setConnectionTestQuery("SELECT 1");

        dataSource = new HikariDataSource(cfg);

        createTable();
    }

    private void createTable() throws SQLException {

        String sql = """
            CREATE TABLE IF NOT EXISTS `variables` (
                `key` VARCHAR(255) NOT NULL,
                `value` TEXT,
                PRIMARY KEY (`key`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    @Override
    public void set(String key, String value) throws Exception {

        String sql = """
            INSERT INTO `variables` (`key`, `value`)
            VALUES (?, ?)
            ON DUPLICATE KEY UPDATE `value` = ?;
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, value);
            ps.executeUpdate();
        }
    }

    @Override
    public String get(String key) throws Exception {

        String sql = "SELECT `value` FROM `variables` WHERE `key` = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, key);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        }
    }

    @Override
    public void delete(String key) throws Exception {

        String sql = "DELETE FROM `variables` WHERE `key` = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    @Override
    public List<String> getAllKeys() throws Exception {

        List<String> list = new ArrayList<>();
        String sql = "SELECT `key` FROM `variables`";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(rs.getString("key"));
            }
        }

        return list;
    }

    @Override
    public Connection getConnection() throws Exception {
        return dataSource.getConnection();
    }

    @Override
    public void close() throws Exception {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
