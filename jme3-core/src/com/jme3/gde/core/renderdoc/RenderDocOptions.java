package com.jme3.gde.core.renderdoc;

import java.awt.*;
import java.beans.*;
import javax.swing.*;
import javax.swing.event.*;
import org.netbeans.spi.options.OptionsPanelController;
import org.openide.*;
import org.openide.util.*;

/** Persistent RenderDoc settings. Enter explicitly saves both fields before asking
 * to restart; Cancel in that prompt keeps the saved settings for the next launch.
 * Apply also saves, whereas cancelling Options discards edits not saved by Enter.
 */
@OptionsPanelController.TopLevelRegistration(categoryName = "RenderDoc",
        iconBase = "com/jme3/gde/core/options/opengl-48.gif",
        keywords = "RenderDoc capture frame GPU", keywordsCategory = "RenderDoc")
public final class RenderDocOptions extends OptionsPanelController {
    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
    private JPanel panel;
    private JCheckBox automatic;
    private JTextField executable;
    private JLabel status;
    private boolean loading;

    private void createPanel() {
        if (panel != null) {
            return;
        }
        panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel fields = new JPanel(new GridLayout(0, 1, 8, 8));
        automatic = new JCheckBox("Automatically inject RenderDoc when SDK starts");
        fields.add(automatic);
        JLabel label = new JLabel("RenderDoc executable (qrenderdoc.exe):");
        fields.add(label);
        JPanel pathRow = new JPanel(new BorderLayout(8, 0));
        executable = new JTextField(40);
        label.setLabelFor(executable);
        pathRow.add(executable, BorderLayout.CENTER);
        JButton browse = new JButton("Browse...");
        pathRow.add(browse, BorderLayout.EAST);
        fields.add(pathRow);
        fields.add(new JLabel("Press Enter to save and choose whether to restart SDK."));
        fields.add(new JLabel("Windows OpenGL; renderdoc.dll must be in the same folder."));
        status = new JLabel();
        fields.add(status);
        panel.add(fields, BorderLayout.NORTH);
        automatic.addActionListener(event -> changed());
        executable.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) {
                changed();
            }
            public void removeUpdate(DocumentEvent event) {
                changed();
            }
            public void changedUpdate(DocumentEvent event) {
                changed();
            }
        });
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                executable.setText(chooser.getSelectedFile().getAbsolutePath());
                executable.requestFocusInWindow();
            }
        });
        executable.addActionListener(event -> saveAndOfferRestart());
    }

    private void changed() {
        if (!loading) {
            String error = RenderDocSupport.validate(executable.getText());
            status.setText(error == null ? "Settings take effect after restarting SDK." : error);
            changes.firePropertyChange(PROP_CHANGED, null, null);
        }
    }

    private void saveAndOfferRestart() {
        applyChanges();
        String error = RenderDocSupport.validate(executable.getText());
        String message = "RenderDoc settings saved. Restart SDK now?";
        if (error != null) {
            message += "\n" + error + "\nCapture will remain unavailable.";
        }
        Object answer = DialogDisplayer.getDefault().notify(new NotifyDescriptor.Confirmation(
                message, "RenderDoc", NotifyDescriptor.YES_NO_OPTION));
        if (NotifyDescriptor.YES_OPTION.equals(answer)) {
            LifecycleManager.getDefault().markForRestart();
            LifecycleManager.getDefault().exit();
        }
    }

    @Override
    public void update() {
        createPanel();
        loading = true;
        automatic.setSelected(RenderDocSupport.automatic());
        executable.setText(RenderDocSupport.path());
        status.setText(RenderDocSupport.status());
        loading = false;
    }

    @Override
    public void applyChanges() {
        if (!SwingUtilities.isEventDispatchThread()) {
            try {
                SwingUtilities.invokeAndWait(this::applyChanges);
            } catch (Exception ex) {
                RenderDocSupport.report(ex);
            }
            return;
        }
        createPanel();
        RenderDocSupport.PREFS.putBoolean("autoInject", automatic.isSelected());
        RenderDocSupport.PREFS.put("executable", executable.getText().trim());
        try {
            RenderDocSupport.PREFS.flush();
        } catch (java.util.prefs.BackingStoreException ex) {
            throw new IllegalStateException("Cannot save RenderDoc settings", ex);
        }
        changes.firePropertyChange(PROP_CHANGED, null, null);
    }

    @Override
    public void cancel() {
        update();
    }

    @Override
    public boolean isValid() {
        return true; // Empty/invalid paths deliberately disable capture and can be saved.
    }

    @Override
    public boolean isChanged() {
        return panel != null && (automatic.isSelected() != RenderDocSupport.automatic()
                || !executable.getText().trim().equals(RenderDocSupport.path()));
    }

    @Override
    public JComponent getComponent(Lookup lookup) {
        createPanel();
        return panel;
    }

    @Override
    public HelpCtx getHelpCtx() {
        return HelpCtx.DEFAULT_HELP;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        changes.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        changes.removePropertyChangeListener(listener);
    }
}
