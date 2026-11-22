package com.jme3.gde.core.assets;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import org.netbeans.api.progress.ProgressHandle;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.windows.WindowManager;

/**
 * One opening operation per file, shared by editor routing and scene attachment.
 * 第一步：点击时 begin；返回 null 表示已有请求，不再启动工作线程。
 * 第二步：将同一个实例作为路由事件 source 交给场景编辑器；第三步：挂载完成或失败后 close。
 * begin/close may run on any thread; Swing is always dispatched to EDT. The
 * modeless dialog shows activity, not a fabricated percentage or cancellation.
 */
public final class AssetOpenProgress implements AutoCloseable {
    private static final ConcurrentHashMap<FileObject, AssetOpenProgress> ACTIVE = new ConcurrentHashMap<>();
    private final FileObject file;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ProgressHandle handle;
    private JDialog dialog;

    private AssetOpenProgress(FileObject file) {
        this.file = file;
        handle = ProgressHandle.createHandle(AnimationEditorMessages.text("Animation.Opening_asset") + " " + file.getNameExt());
    }

    public static AssetOpenProgress begin(DataObject data) {
        AssetOpenProgress progress = new AssetOpenProgress(data.getPrimaryFile());
        AssetOpenProgress previous = ACTIVE.putIfAbsent(progress.file, progress);
        if (previous != null) {
            EventQueue.invokeLater(() -> {
                if (previous.dialog != null) {
                    previous.dialog.toFront();
                }
            });
            return null;
        }
        progress.handle.setInitialDelay(0);
        progress.handle.start();
        EventQueue.invokeLater(progress::show);
        return progress;
    }

    public static AssetOpenProgress current(DataObject data) {
        return data == null ? null : ACTIVE.get(data.getPrimaryFile());
    }

    private void show() {
        if (closed.get() || GraphicsEnvironment.isHeadless()) {
            return;
        }
        dialog = new JDialog(WindowManager.getDefault().getMainWindow(),
                AnimationEditorMessages.text("Animation.Opening_asset"), false);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        JLabel name = new JLabel(file.getNameExt());
        name.setToolTipText(file.getPath());
        content.add(name, BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        content.add(bar, BorderLayout.CENTER);
        content.add(new JLabel(AnimationEditorMessages.text("Animation.Opening_asset_wait")), BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(440, 150);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            ACTIVE.remove(file, this);
            handle.finish();
            EventQueue.invokeLater(() -> {
                if (dialog != null) {
                    dialog.dispose();
                    dialog = null;
                }
            });
        }
    }
}
