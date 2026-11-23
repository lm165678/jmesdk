package com.jme3.gde.unityscene;

import com.jme3.gde.core.assets.ProjectAssetManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.SourceVersion;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.RequestProcessor;

/**
 * 第一步：把 Unity 资产及 .meta 放入游戏工程 Assets。
 * 第二步：右键 .unity 或 .prefab，选择 Export Unity J3O with Controls，
 * 输入游戏包名，生成代码放入该包的 controls 子包。
 * 第三步：让游戏依赖 jme3-unity-runtime，编译工程并在游戏中挂 UnityBehaviourState。
 * 第四步：打开导出的 J3O 验证资源，不支持的脚本以被动数据保留。
 */
@ActionID(category = "jMonkeyPlatform", id = "com.jme3.gde.unityscene.ExportUnityProjectAction")
@ActionRegistration(displayName = "#LBL_UNITY_EXPORT_RUNTIME")
@ActionReference(path = "Loaders/application/x-unity-scene/Actions", position = 150)
public final class ExportUnityProjectAction implements ActionListener {
    private final UnitySceneDataObject data;

    public ExportUnityProjectAction(UnitySceneDataObject data) {
        this.data = data;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        ProjectAssetManager assets = data.getLookup().lookup(ProjectAssetManager.class);
        if (assets == null || assets.getProject() == null || assets.getAssetFolder() == null
                || FileUtil.toFile(assets.getAssetFolder()) == null) {
            report("Select a Unity asset in a local Java game project.", NotifyDescriptor.ERROR_MESSAGE);
            return;
        }
        NotifyDescriptor.InputLine prompt = new NotifyDescriptor.InputLine(
                "Game package (generated controls go in the controls subpackage):", "Export Unity J3O with Controls");
        if (!NotifyDescriptor.OK_OPTION.equals(DialogDisplayer.getDefault().notify(prompt))) {
            return;
        }
        String base = prompt.getInputText().trim();
        if (!base.isEmpty() && (!SourceVersion.isName(base) || base.equals("java") || base.startsWith("java."))) {
            report("Enter a valid Java package name.", NotifyDescriptor.ERROR_MESSAGE);
            return;
        }
        String packageName = base.isEmpty() ? "controls" : base + ".controls";
        FileObject input = data.getPrimaryFile();
        RequestProcessor.getDefault().post(() -> {
            try {
                Path output = UnityProjectExport.write(input, assets, sourceRoot(assets.getProject()), packageName);
                report("Exported " + output + "\nPackage: " + packageName
                        + "\nReview Unity import warnings in the IDE log and the exported scene metadata.",
                        NotifyDescriptor.INFORMATION_MESSAGE);
            } catch (IOException | RuntimeException failure) {
                report("Unity export failed: " + failure.getMessage(), NotifyDescriptor.ERROR_MESSAGE);
            }
        });
    }

    private static Path sourceRoot(Project project) throws IOException {
        FileObject conventional = project.getProjectDirectory().getFileObject("src/main/java");
        List<FileObject> roots = new ArrayList<>();
        for (var group : ProjectUtils.getSources(project).getSourceGroups("java")) {
            FileObject root = group.getRootFolder();
            if (root.equals(conventional)) {
                return FileUtil.toFile(root).toPath();
            }
            if (FileUtil.toFile(root) != null && !roots.contains(root)) {
                roots.add(root);
            }
        }
        if (roots.size() == 1) {
            return FileUtil.toFile(roots.get(0)).toPath();
        }
        throw new IOException("Could not identify a unique Java source root for this project.");
    }

    private static void report(String message, int kind) {
        DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(message, kind));
    }
}
