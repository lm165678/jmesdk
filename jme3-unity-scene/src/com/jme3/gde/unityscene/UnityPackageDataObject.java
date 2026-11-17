package com.jme3.gde.unityscene;

import java.io.IOException;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.MIMEResolver;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectExistsException;
import org.openide.loaders.MultiDataObject;
import org.openide.loaders.MultiFileLoader;
import org.openide.util.NbBundle.Messages;

/** Package node; archives must be restored before their scenes can be converted. */
@Messages("LBL_UnityPackage=Unity Package")
@MIMEResolver.ExtensionRegistration(displayName = "#LBL_UnityPackage",
        mimeType = "application/x-unity-package", extension = {"unitypackage"})
@DataObject.Registration(mimeType = "application/x-unity-package",
        iconBase = "com/jme3/gde/unityscene/UnityScene.png", displayName = "#LBL_UnityPackage", position = 301)
public final class UnityPackageDataObject extends MultiDataObject {
    public UnityPackageDataObject(FileObject file, MultiFileLoader loader)
            throws DataObjectExistsException, IOException {
        super(file, loader);
    }

    @Override
    protected int associateLookup() {
        return 1;
    }
}
