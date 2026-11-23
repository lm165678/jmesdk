package com.jme3.gde.core.assets.actions;

import com.jme3.gde.core.assets.SpirvCross;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;
import org.openide.filesystems.FileUtil;
import org.openide.util.NbBundle.Messages;
import org.openide.util.RequestProcessor;
import org.openide.windows.WindowManager;

/**
 * 第一步：从 Tools 选择 Convert SPIR-V to GLSL，打开编译后的 .spv。
 * 第二步：选择源阶段、入口点及 OpenGL GLSL 版本，保存对应阶段的 GLSL。
 * 第三步：在项目的 .j3md 中引用 GLSL 并声明它使用的参数。
 */
@ActionID(category = "Tools", id = "com.jme3.gde.core.assets.actions.ConvertSpirvAction")
@ActionRegistration(displayName = "#CTL_ConvertSpirvToGlsl")
@ActionReference(path = "Menu/Tools", position = 1730)
@Messages("CTL_ConvertSpirvToGlsl=Convert SPIR-V to GLSL...")
public final class ConvertSpirvAction implements ActionListener {
    private record Target(int version, boolean es) {
        @Override
        public String toString() {
            return es ? "GLSL ES " + version : "GLSL " + version;
        }
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        var parent = WindowManager.getDefault().getMainWindow();
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Select compiled SPIR-V");
        chooser.setFileFilter(new FileNameExtensionFilter("SPIR-V binary (.spv)", "spv"));
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path input = chooser.getSelectedFile().toPath().toAbsolutePath();
        String fileName = input.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".spv")) {
            report("Select a SPIR-V binary (.spv).", NotifyDescriptor.ERROR_MESSAGE);
            return;
        }
        String base = fileName.substring(0, fileName.length() - 4);
        var stage = new JComboBox<>(SpirvCross.Stage.values());
        stage.setSelectedItem(SpirvCross.Stage.FRAGMENT);
        for (SpirvCross.Stage value : SpirvCross.Stage.values()) {
            if (base.toLowerCase(Locale.ROOT).endsWith("." + value.extension())) {
                stage.setSelectedItem(value);
                break;
            }
        }
        var entry = new JTextField("main");
        var target = new JComboBox<>(new Target[]{new Target(150, false), new Target(330, false),
            new Target(430, false), new Target(450, false), new Target(300, true), new Target(310, true),
            new Target(320, true)});
        target.setSelectedIndex(1);
        var options = new JPanel(new GridLayout(0, 2, 5, 5));
        options.add(new JLabel("Shader stage"));
        options.add(stage);
        options.add(new JLabel("SPIR-V entry point"));
        options.add(entry);
        options.add(new JLabel("GLSL target"));
        options.add(target);
        var dialog = new NotifyDescriptor.Confirmation(options, "Convert SPIR-V to GLSL",
                NotifyDescriptor.OK_CANCEL_OPTION, NotifyDescriptor.PLAIN_MESSAGE);
        if (!NotifyDescriptor.OK_OPTION.equals(DialogDisplayer.getDefault().notify(dialog))) {
            return;
        }
        SpirvCross.Stage selected = (SpirvCross.Stage) stage.getSelectedItem();
        Target profile = (Target) target.getSelectedItem();
        String entryPoint = entry.getText().trim();
        String extension = "." + selected.extension();
        String suggestedName = base.toLowerCase(Locale.ROOT).endsWith(extension) ? base : base + extension;
        var output = new JFileChooser(input.getParent().toFile());
        output.setDialogTitle("Save GLSL as a new file");
        output.setSelectedFile(input.resolveSibling(suggestedName).toFile());
        if (output.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path destination = output.getSelectedFile().toPath().toAbsolutePath();
        RequestProcessor.getDefault().post(() -> convert(input, destination, selected, entryPoint, profile));
    }

    private static void convert(Path input, Path output, SpirvCross.Stage stage, String entryPoint, Target target) {
        try {
            String glsl = SpirvCross.toGlsl(Files.readAllBytes(input), stage, entryPoint, target.version(), target.es());
            Files.writeString(output, glsl, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            FileUtil.refreshFor(output.toFile());
            report("Saved " + output, NotifyDescriptor.INFORMATION_MESSAGE);
        } catch (IOException | IllegalArgumentException failure) {
            report("SPIR-V conversion failed: " + failure.getMessage(), NotifyDescriptor.ERROR_MESSAGE);
        }
    }

    private static void report(String text, int kind) {
        DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(text, kind));
    }
}
