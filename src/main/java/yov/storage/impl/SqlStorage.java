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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import yov.YOVPlugin;
import yov.storage.StorageBackend;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SqlStorage implements StorageBackend {

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String pass;

    private final boolean useSSL;
    private final boolean allowPublicKeyRetrieval;
    private final String serverTimezone;
    private final String sessionVariables;

    private final int maximumPoolSize;
    private final int minimumIdle;
    private final long connectionTimeout;
    private final long idleTimeout;
    private final long maxLifetime;
    private final boolean cachePrepStmts;
    private final int prepStmtCacheSize;
    private final int prepStmtCacheSqlLimit;
    private final String poolName;
    private final long initializationFailTimeout;
    private final String connectionTestQuery;

    private final String tableName;
    private final String keyColumn;
    private final String valueColumn;
    private final String tableEngine;
    private final String tableCharset;

    private HikariDataSource dataSource;

    public SqlStorage(String backend,
                      String host,
                      int port,
                      String database,
                      String user,
                      String pass) {

        YOVPlugin plugin = JavaPlugin.getPlugin(YOVPlugin.class);
        FileConfiguration cfg = plugin.getConfig();

        this.host = cfg.getString("storage.mariadb.host", host);
        this.port = cfg.getInt("storage.mariadb.port", port);
        this.database = cfg.getString("storage.mariadb.database", database);
        this.user = cfg.getString("storage.mariadb.username", user);
        this.pass = cfg.getString("storage.mariadb.password", pass);

        this.useSSL = cfg.getBoolean("storage.mariadb.use-ssl", false);
        this.allowPublicKeyRetrieval = cfg.getBoolean("storage.mariadb.allow-public-key-retrieval", true);
        this.serverTimezone = cfg.getString("storage.mariadb.server-timezone", "UTC");
        this.sessionVariables = cfg.getString("storage.mariadb.session-variables", "sql_mode=''");

        this.maximumPoolSize = cfg.getInt("storage.mariadb.hikari.maximum-pool-size", 5);
        this.minimumIdle = cfg.getInt("storage.mariadb.hikari.minimum-idle", 1);
        this.connectionTimeout = cfg.getLong("storage.mariadb.hikari.connection-timeout", 5000L);
        this.idleTimeout = cfg.getLong("storage.mariadb.hikari.idle-timeout", 60000L);
        this.maxLifetime = cfg.getLong("storage.mariadb.hikari.max-lifetime", 30 * 60 * 1000L);
        this.cachePrepStmts = cfg.getBoolean("storage.mariadb.hikari.cache-prep-stmts", true);
        this.prepStmtCacheSize = cfg.getInt("storage.mariadb.hikari.prep-stmt-cache-size", 250);
        this.prepStmtCacheSqlLimit = cfg.getInt("storage.mariadb.hikari.prep-stmt-cache-sql-limit", 2048);
        this.poolName = cfg.getString("storage.mariadb.hikari.pool-name", "YOV-POOL");
        this.initializationFailTimeout = cfg.getLong("storage.mariadb.hikari.initialization-fail-timeout", -1L);
        this.connectionTestQuery = cfg.getString("storage.mariadb.hikari.connection-test-query", "SELECT 1");

        this.tableName = cfg.getString("storage.mariadb.table", "variables");
        this.keyColumn = cfg.getString("storage.mariadb.key-column", "key");
        this.valueColumn = cfg.getString("storage.mariadb.value-column", "value");
        this.tableEngine = cfg.getString("storage.mariadb.table-engine", "InnoDB");
        this.tableCharset = cfg.getString("storage.mariadb.table-charset", "utf8mb4");
    }

    @Override
    public void connect() throws Exception {

        Class.forName("com.mysql.cj.jdbc.Driver");
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + "&allowPublicKeyRetrieval=" + allowPublicKeyRetrieval
                + "&serverTimezone=" + serverTimezone
                + "&sessionVariables=" + sessionVariables;

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(pass);

        cfg.setMaximumPoolSize(maximumPoolSize);
        cfg.setMinimumIdle(minimumIdle);
        cfg.setConnectionTimeout(connectionTimeout);
        cfg.setIdleTimeout(idleTimeout);
        cfg.setMaxLifetime(maxLifetime);

        cfg.addDataSourceProperty("cachePrepStmts", String.valueOf(cachePrepStmts));
        cfg.addDataSourceProperty("prepStmtCacheSize", String.valueOf(prepStmtCacheSize));
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", String.valueOf(prepStmtCacheSqlLimit));

        cfg.setPoolName(poolName);
        cfg.setInitializationFailTimeout(initializationFailTimeout);
        cfg.setConnectionTestQuery(connectionTestQuery);

        dataSource = new HikariDataSource(cfg);

        createTable();
    }

    private void createTable() throws SQLException {

        String sql = "CREATE TABLE IF NOT EXISTS `" + tableName + "` (" +
                " `" + keyColumn + "` VARCHAR(255) NOT NULL," +
                " `" + valueColumn + "` TEXT," +
                " PRIMARY KEY (`" + keyColumn + "`)" +
                ") ENGINE=" + tableEngine + " DEFAULT CHARSET=" + tableCharset + ";";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    @Override
    public void set(String key, String value) throws Exception {

        String sql = "INSERT INTO `" + tableName + "` (`" + keyColumn + "`, `" + valueColumn + "`) " +
                "VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE `" + valueColumn + "` = ?;";

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

        String sql = "SELECT `" + valueColumn + "` FROM `" + tableName + "` WHERE `" + keyColumn + "` = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, key);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(valueColumn) : null;
            }
        }
    }

    @Override
    public void delete(String key) throws Exception {

        String sql = "DELETE FROM `" + tableName + "` WHERE `" + keyColumn + "` = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    @Override
    public List<String> getAllKeys() throws Exception {

        List<String> list = new ArrayList<>();
        String sql = "SELECT `" + keyColumn + "` FROM `" + tableName + "`";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(rs.getString(keyColumn));
            }
        }

        return list;
    }

    @Override
    public List<String> getKeysByPrefix(String prefix) throws Exception {
        List<String> list = new ArrayList<>();
        String sql = "SELECT `" + keyColumn + "` FROM `" + tableName + "` WHERE `" + keyColumn + "` LIKE ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, prefix + "%");

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(keyColumn));
                }
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
