package com.jme3.gde.gui.completion;

import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.Iterator;
import org.netbeans.modules.xml.catalog.spi.CatalogReader;
import org.netbeans.modules.xml.catalog.spi.CatalogDescriptor2;
import org.netbeans.modules.xml.catalog.spi.CatalogListener;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

/** Offline schema for XML completion and validation in Nifty files. */
public final class NiftyCatalog implements CatalogReader, CatalogDescriptor2, EntityResolver {
    private static final String NAMESPACE = "http://nifty-gui.lessvoid.com/nifty-gui";
    private static final String SCHEMA = NAMESPACE + "/nifty.xsd";

    @Override
    public Iterator<String> getPublicIDs() {
        return Collections.singleton("SCHEMA:" + NAMESPACE).iterator();
    }

    private String resolve(String id) {
        if (NAMESPACE.equals(id) || SCHEMA.equals(id)
                || ("SCHEMA:" + NAMESPACE).equals(id)
                || "https://raw.githubusercontent.com/void256/nifty-gui/1.4/nifty-core/src/main/resources/nifty.xsd".equals(id)) {
            return NiftyCatalog.class.getResource("nifty.xsd").toExternalForm();
        }
        return null;
    }

    @Override
    public String getSystemID(String id) {
        return resolve(id);
    }

    @Override
    public String resolveURI(String id) {
        return resolve(id);
    }

    @Override
    public String resolvePublic(String id) {
        return resolve(id);
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId) {
        String resolved = resolve(systemId);
        if (resolved == null) {
            resolved = resolve(publicId);
        }
        return resolved == null ? null : new InputSource(resolved);
    }

    @Override
    public String getDisplayName() {
        return "Nifty GUI 1.4 (offline)";
    }

    @Override
    public String getShortDescription() {
        return "Nifty elements, attributes and schema value completion";
    }

    @Override
    public String getIconResource(int type) {
        return "com/jme3/gde/gui/multiview/icons/gui-icon.png";
    }

    @Override
    public void refresh() {
    }

    @Override
    public void addCatalogListener(CatalogListener listener) {
    }

    @Override
    public void removeCatalogListener(CatalogListener listener) {
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {
    }
}
