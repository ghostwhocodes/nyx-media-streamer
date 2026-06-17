package com.nyx.eforms;

import com.nyx.common.DatabaseFactory;
import com.nyx.common.DatabaseResources;
import com.nyx.config.DatabaseConfig;
import java.nio.file.Path;

public final class EFormsDatabase {
    private EFormsDatabase() {
    }

    public static DatabaseResources createDatabase(Path dbDir) {
        return createDatabase(dbDir, new DatabaseConfig(dbDir, 1, 600_000L, 1_800_000L));
    }

    public static DatabaseResources createDatabase(Path dbDir, DatabaseConfig dbConfig) {
        return DatabaseFactory.create(dbDir, "eforms", dbConfig);
    }
}
