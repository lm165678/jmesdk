package com.jme3.gde.texturetools;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import org.netbeans.modules.masterfs.providers.BaseAnnotationProvider;
import org.netbeans.modules.masterfs.providers.InterceptionListener;
import org.netbeans.modules.masterfs.providers.ProvidedExtensions;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.ServiceProvider;

/**
 * Applies ownership policy to NetBeans local filesystem operations, including
 * project trees, asset browsers, and folder operations. Uses the NetBeans 31
 * masterfs implementation SPI (implementation version 2); validate on upgrades.
 * OS file managers bypass this interceptor. Missing/modified external outputs
 * are detected by registry hashes and regenerated on the next conversion.
 */
@ServiceProvider(service = BaseAnnotationProvider.class, position = 10)
public final class GeneratedTextureFiles extends BaseAnnotationProvider {
    private final ProvidedExtensions interceptor = new ProvidedExtensions() {
        @Override
        public IOHandler getCopyHandler(File from, File to) {
            GeneratedTextures store = GeneratedTextures.containing(from.toPath());
            if (store == null) {
                return null;
            }
            try {
                store.checkCopy(from.toPath());
                return null;
            } catch (IOException failure) {
                return () -> {
                    throw failure;
                };
            }
        }

        @Override
        public IOHandler getMoveHandler(File from, File to) {
            GeneratedTextures store = GeneratedTextures.containing(from.toPath());
            if (store == null) {
                return null;
            }
            if (store.isRoot(from.toPath())) {
                return null;
            }
            try {
                if (!store.affects(from.toPath())) {
                    return null;
                }
            } catch (IOException failure) {
                return () -> {
                    throw failure;
                };
            }
            return () -> store.move(from.toPath(), to.toPath());
        }

        @Override
        public IOHandler getRenameHandler(File from, String newName) {
            return getMoveHandler(from, new File(from.getParentFile(), newName));
        }

        @Override
        public DeleteHandler getDeleteHandler(File file) {
            GeneratedTextures store = GeneratedTextures.containing(file.toPath());
            if (store == null) {
                return null;
            }
            try {
                if (!store.affects(file.toPath())) {
                    return null;
                }
            } catch (IOException failure) {
                return target -> {
                    throw new java.io.UncheckedIOException(failure.getMessage(), failure);
                };
            }
            return target -> {
                try {
                    store.delete(target.toPath());
                    return true;
                } catch (IOException failure) {
                    // Returning false lets masterfs attempt recursive fallback
                    // deletion, potentially removing unrelated children first.
                    // Abort the whole operation before that fallback can run.
                    throw new java.io.UncheckedIOException(failure.getMessage(), failure);
                }
            };
        }
    };

    @Override
    public String annotateName(String name, Set<? extends FileObject> files) {
        return null;
    }

    @Override
    public String annotateNameHtml(String name, Set<? extends FileObject> files) {
        return null;
    }

    @Override
    public InterceptionListener getInterceptionListener() {
        return interceptor;
    }
}
