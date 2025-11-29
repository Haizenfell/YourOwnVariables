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
package yov.storage;

import java.sql.Connection;
import java.util.List;

public interface StorageBackend {

    void connect() throws Exception;

    void set(String key, String value) throws Exception;

    String get(String key) throws Exception;

    void delete(String key) throws Exception;

    List<String> getAllKeys() throws Exception;

    Connection getConnection() throws Exception;

    void close() throws Exception;
}
