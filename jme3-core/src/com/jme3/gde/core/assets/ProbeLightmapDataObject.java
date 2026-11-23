package com.jme3.gde.core.assets;

import java.awt.event.ActionEvent;
import java.io.IOException;
import javax.swing.AbstractAction;
import javax.swing.Action;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataNode;
import org.openide.loaders.DataObject;
import org.openide.loaders.MultiDataObject;
import org.openide.loaders.MultiFileLoader;
import org.openide.nodes.Children;
import org.openide.nodes.Node;

/** Provides only the Lightmap preview action for these J3O assets. */
@DataObject.Registration(mimeType = ProbeLightmapResolver.MIME, displayName = "Probe Lightmap", position = 100)
public final class ProbeLightmapDataObject extends MultiDataObject {
    public ProbeLightmapDataObject(FileObject file, MultiFileLoader loader) throws IOException {
        super(file, loader);
        getCookieSet().add((OpenCookie) () -> ProbeLightmapTopComponent.openPreview(getPrimaryFile()));
    }

    @Override
    protected int associateLookup() {
        return 1;
    }

    @Override
    protected Node createNodeDelegate() {
        return new DataNode(this, Children.LEAF, getLookup()) {
            @Override
            public Action getPreferredAction() {
                return new AbstractAction("Open Lightmap Preview") {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        getLookup().lookup(OpenCookie.class).open();
                    }
                };
            }
        };
    }
}
