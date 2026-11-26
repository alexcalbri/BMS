package com.ideasdeveloper.bms.server.i18n

import com.ideasdeveloper.bms.server.extensions.ServerExtension
import com.ideasdeveloper.bms.server.modules.ServerModule
import org.slf4j.LoggerFactory

/*
 * Catalogs of modules and extensions (I18N.md §3), read through the class loader of the package that
 * declares them, so a packaged module or extension finds the files inside its own jar. The paths are
 * namespaced because class loaders are parent-first.
 */

private val log = LoggerFactory.getLogger("I18nCatalogs")

/** Resource folder of the catalogs of module [moduleId]: `modules/<id>/i18n`. */
fun moduleCatalogDir(moduleId: String) = "modules/$moduleId/i18n"

/** Resource folder of the catalogs of extension [extensionId]: `extensions/<id>/i18n`. */
fun extensionCatalogDir(extensionId: String) = "extensions/$extensionId/i18n"

/** Loads the catalogs of [module] (called by ModuleRegistry when the module is loaded; idempotent). */
fun loadModuleCatalog(module: ServerModule) {
    val id = try {
        module.definition.id
    } catch (failure: Throwable) {
        log.warn("Catalog of a module not loaded: its definition failed", failure)
        return
    }
    I18n.loadFrom(module.javaClass.classLoader, moduleCatalogDir(id), id)
}

/** Loads the catalogs of [extension] (called by ExtensionRegistry when it is built; idempotent). */
fun loadExtensionCatalog(extension: ServerExtension) {
    val id = try {
        extension.manifest.id
    } catch (failure: Throwable) {
        log.warn("Catalog of an extension not loaded: its manifest failed", failure)
        return
    }
    I18n.loadFrom(extension.javaClass.classLoader, extensionCatalogDir(id), "extensions/$id")
}
