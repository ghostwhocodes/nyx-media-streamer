package com.nyx.eforms;

public final class EFormsBindings {
    private final EFormsPersistenceResources resources;
    private final EFormService eFormService;
    private final ExportImportService exportImportService;
    private final RelocationService relocationService;

    public EFormsBindings(
        EFormsPersistenceResources resources,
        EFormService eFormService,
        ExportImportService exportImportService,
        RelocationService relocationService
    ) {
        this.resources = resources;
        this.eFormService = eFormService;
        this.exportImportService = exportImportService;
        this.relocationService = relocationService;
    }

    public EFormsPersistenceResources getResources() {
        return resources;
    }

    public EFormService getEFormService() {
        return eFormService;
    }

    public ExportImportService getExportImportService() {
        return exportImportService;
    }

    public RelocationService getRelocationService() {
        return relocationService;
    }
}
