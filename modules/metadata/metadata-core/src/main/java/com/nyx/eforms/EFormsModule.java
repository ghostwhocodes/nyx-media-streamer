package com.nyx.eforms;

import com.nyx.config.ServerConfig;

public final class EFormsModule {
    private EFormsModule() {
    }

    public static EFormsBindings createEFormsBindings(ServerConfig config) {
        EFormsPersistenceResources resources = EFormsPersistenceResources.create(config);
        EFormService eFormService = new EFormService(resources.getJdbi());
        return new EFormsBindings(
            resources,
            eFormService,
            new ExportImportService(eFormService),
            new RelocationService(resources.getJdbi())
        );
    }
}
