package com.jme3.gde.gui.propertyeditors;

import jada.ngeditor.controller.CommandProcessor;
import jada.ngeditor.model.GUI;
import java.awt.Component;
import java.beans.PropertyEditorSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

/** Asset-relative paths; the chooser captures its owner when opened on the EDT. */
public class ResourceEditor extends PropertyEditorSupport {
    @Override
    public Component getCustomEditor() {
        GUI gui = CommandProcessor.getInstance().getGuiEditor().getGui();
        JFileChooser chooser = new JFileChooser(gui.getAssetFolder());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.addActionListener(event -> {
            if (!JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())) {
                return;
            }
            try {
                Path root = gui.getAssetFolder().toPath().toRealPath();
                Path file = chooser.getSelectedFile().toPath().toRealPath();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    throw new IOException("Select a file inside the assets folder.");
                }
                setValue(root.relativize(file).toString().replace((char) 92, '/'));
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(chooser, ex.getMessage());
            }
        });
        return chooser;
    }

    @Override
    public boolean supportsCustomEditor() {
        return true;
    }
}
