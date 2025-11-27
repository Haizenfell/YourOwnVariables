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

public interface BatchCapable {
    void beginBatch() throws Exception;
    void endBatch() throws Exception;
}
