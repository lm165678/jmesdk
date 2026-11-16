package com.jme3.gde.gui.nodes;

import jada.ngeditor.model.GUI;
import jada.ngeditor.model.elements.specials.GUseControls;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.Children;

public class GUseControlsNode extends AbstractNode {
      private static final String basePath="com/jme3/gde/gui/multiview/icons";
    private final GUseControls controls;
    private final GUI gui;
      
    public GUseControlsNode(GUI gui,GUseControls controls) {
        super(Children.LEAF);
        this.setIconBaseWithExtension(basePath+"/"+"control"+".png");
        this.controls = controls;
        this.gui = gui;
        this.setName(controls.getFilename());
    }
    
    
    @Override
    public javax.swing.Action[] getActions(boolean context) {
        return new javax.swing.Action[]{new javax.swing.AbstractAction("Delete") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ReferenceEdit.perform(gui, () -> gui.removeControl(controls),
                        () -> gui.addLoadUseControls(controls));
            }
        }};
    }
}