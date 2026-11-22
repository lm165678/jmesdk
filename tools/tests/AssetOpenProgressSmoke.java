package com.jme3.gde.core.assets;

import java.awt.EventQueue;
import java.awt.Window;
import java.util.concurrent.CountDownLatch;
import javax.swing.JDialog;
import javax.swing.JProgressBar;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;

/** Exercises the real Swing loading dialog and per-file opening guard. */
public final class AssetOpenProgressSmoke {
    public static void main(String[] args) throws Exception {
        try {
            var fs = FileUtil.createMemoryFileSystem();
            DataObject data = DataObject.find(fs.getRoot().createData("SlowScene.j3o"));
            AssetOpenProgress progress = AssetOpenProgress.begin(data);
            if (progress == null || AssetOpenProgress.begin(data) != null) {
                throw new AssertionError("Repeated clicks must share one opening operation");
            }
            EventQueue.invokeAndWait(() -> {
                JDialog dialog = dialog(progress);
                if (!dialog.isShowing() || dialog.isModal() || dialog.getWidth() > 500) {
                    throw new AssertionError("Opening needs a compact, visible, modeless dialog");
                }
                boolean bar = false;
                for (var component : dialog.getContentPane().getComponents()) {
                    if (component instanceof JProgressBar indicator) {
                        bar = indicator.isIndeterminate();
                    }
                }
                if (!bar) {
                    throw new AssertionError("Opening must show an indeterminate progress bar");
                }
            });
            CountDownLatch ready = new CountDownLatch(1);
            Thread loader = new Thread(() -> {
                try {
                    ready.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    progress.close();
                    progress.close();
                }
            });
            loader.start();
            if (AssetOpenProgress.current(data) != progress) {
                throw new AssertionError("Guard must survive until background work finishes");
            }
            ready.countDown();
            loader.join(5000);
            EventQueue.invokeAndWait(() -> {
                if (AssetOpenProgress.current(data) != null || dialog(progress) != null) {
                    throw new AssertionError("Completion must release dialog and guard");
                }
            });
            EventQueue.invokeAndWait(() -> {
                AssetOpenProgress retry = AssetOpenProgress.begin(data);
                if (retry == null) {
                    throw new AssertionError("A completed or failed operation must allow retry");
                }
                retry.close();
            });
            EventQueue.invokeAndWait(() -> { });
            if (AssetOpenProgress.current(data) != null) {
                throw new AssertionError("Fast completion must not resurrect the dialog");
            }
            System.out.println("ASSET_OPEN_PROGRESS_SWING_PASS");
        } finally {
            EventQueue.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    window.dispose();
                }
            });
        }
    }

    private static JDialog dialog(AssetOpenProgress progress) {
        try {
            var field = AssetOpenProgress.class.getDeclaredField("dialog");
            field.setAccessible(true);
            return (JDialog) field.get(progress);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
