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
 * 第二步：将同一个实例作为路由事件 source 交给场景编辑器；第三步：首帧呈现或失败后 close。
 * begin/close may run on any thread; Swing is always dispatched to EDT. The
 * modeless dialog shows activity, not a fabricated percentage or cancellation.
 */
public final class AssetOpenProgress implements AutoCloseable {
    private static final ConcurrentHashMap<FileObject, AssetOpenProgress> ACTIVE = new ConcurrentHashMap<>();
    private final FileObject file;
    private final String title;
    private final String label;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ProgressHandle handle;
    private JDialog dialog;
    private JLabel detail;

    private AssetOpenProgress(FileObject file) {
        this(file, AnimationEditorMessages.text("Animation.Opening_asset"), file.getNameExt());
    }

    private AssetOpenProgress(FileObject file, String title, String label) {
        this.file = file;
        this.title = title;
        this.label = label;
        handle = ProgressHandle.createHandle(title + " " + label);
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
        progress.start();
        return progress;
    }

    /**
     * 第一步：后台开始扫描文件时显示导入进度。第二步：弹出转换选项时暂时隐藏，
     * 确认后恢复。第三步：完成、失败或取消时关闭窗口。
     */
    static AssetOpenProgress beginImport(String label) {
        var progress = new AssetOpenProgress(null, "Importing assets", label);
        progress.start();
        return progress;
    }

    private void start() {
        handle.setInitialDelay(0);
        handle.start();
        EventQueue.invokeLater(this::show);
    }

    /**
     * Reports the rendering phase without finishing the opening operation.
     * 第一步：完成资源读取后调用本方法；第二步：等待当前场景首帧呈现；
     * 第三步：呈现、失败或放弃该请求时 close。可从任意线程调用。
     */
    public void waitingForFirstFrame() {
        phase("Preparing first frame...");
    }

    void phase(String message) {
        handle.progress(message);
        EventQueue.invokeLater(() -> {
            if (detail != null) {
                detail.setText(message);
            }
        });
    }

    // Called on EDT before and after the modal conversion options.
    void setDialogVisible(boolean visible) {
        if (visible) {
            show();
        } else if (dialog != null) {
            dialog.setVisible(false);
        }
    }

    public static AssetOpenProgress current(DataObject data) {
        return data == null ? null : ACTIVE.get(data.getPrimaryFile());
    }

    private void show() {
        if (closed.get() || GraphicsEnvironment.isHeadless()) {
            return;
        }
        if (dialog != null) {
            dialog.setVisible(true);
            return;
        }
        dialog = new JDialog(WindowManager.getDefault().getMainWindow(), title, false);
        if (file == null) {
            dialog.setAutoRequestFocus(false);
        }
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        JLabel name = new JLabel(label);
        name.setToolTipText(file == null ? label : file.getPath());
        content.add(name, BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        content.add(bar, BorderLayout.CENTER);
        detail = new JLabel(file == null ? "Checking files..." : AnimationEditorMessages.text("Animation.Opening_asset_wait"));
        content.add(detail, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(440, 150);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (file != null) {
                ACTIVE.remove(file, this);
            }
            handle.finish();
            EventQueue.invokeLater(() -> {
                if (dialog != null) {
                    dialog.dispose();
                    dialog = null;
                    detail = null;
                }
            });
        }
    }
}
