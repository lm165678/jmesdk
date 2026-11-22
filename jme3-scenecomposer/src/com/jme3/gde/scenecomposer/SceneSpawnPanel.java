package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.gde.core.sceneexplorer.nodes.actions.NewLightPopup;
import com.jme3.gde.core.sceneexplorer.nodes.actions.NewSpatialPopup;
import com.jme3.gde.core.sceneexplorer.nodes.actions.SceneSpawnTransfer;
import com.jme3.scene.Node;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

/** Palette for the same creation commands available in the scene explorer. */
final class SceneSpawnPanel extends JPanel {
    private final JPanel spatials = group("Spatial");
    private final JPanel lights = group("Light");

    SceneSpawnPanel() {
        setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
        add(spatials);
        add(lights);
    }

    private static JPanel group(String title) {
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 3));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        return panel;
    }

    void setScene(SceneRequest request) {
        spatials.removeAll();
        lights.removeAll();
        if (request != null && request.getDataObject() != null && request.getManager() != null
                && request.getRootNode() instanceof Node && request.getJmeNode() != null
                && !request.getJmeNode().isReadOnly()) {
            addCommands(spatials, (JMenu) new NewSpatialPopup(request.getJmeNode()).getPopupPresenter(), request);
            addCommands(lights, (JMenu) new NewLightPopup(request.getJmeNode()).getPopupPresenter(), request);
        }
        Container section = getParent();
        if (section != null) {
            section.setMinimumSize(new Dimension(0, section.getPreferredSize().height));
        }
        revalidate();
        repaint();
    }

    private static void addCommands(JPanel group, JMenu menu, SceneRequest request) {
        for (Component component : menu.getMenuComponents()) {
            if (component instanceof JMenu submenu) {
                addCommands(group, submenu, request);
            } else if (component instanceof JMenuItem item && item.getAction() != null) {
                group.add(dragButton(item, request));
            }
        }
    }

    private static JButton dragButton(JMenuItem item, SceneRequest request) {
        Action action = item.getAction();
        JButton button = new JButton(item.getText(), item.getIcon());
        button.setHorizontalAlignment(JButton.LEADING);
        button.setEnabled(action.isEnabled());
        button.setToolTipText("Drag into the scene to create " + item.getText());
        button.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent source) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent source) {
                return new SceneSpawnTransfer(request, action);
            }
        });
        button.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                if (button.isEnabled() && SwingUtilities.isLeftMouseButton(event)) {
                    button.getTransferHandler().exportAsDrag(button, event, TransferHandler.COPY);
                }
            }
        });
        return button;
    }
}
