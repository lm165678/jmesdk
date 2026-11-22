package com.jme3.gde.core.sceneexplorer.nodes.actions;

import com.jme3.gde.core.scene.SceneRequest;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import javax.swing.Action;

/** Carries a creation action from the palette to the viewport in the same SDK process. */
public record SceneSpawnTransfer(SceneRequest request, Action action) implements Transferable {
    public static final DataFlavor FLAVOR = createFlavor();

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + SceneSpawnTransfer.class.getName(),
                    "Scene object", SceneSpawnTransfer.class.getClassLoader());
        } catch (ClassNotFoundException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return this;
    }
}
