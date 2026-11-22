package com.jme3.gde.core.sceneexplorer.nodes;

import com.jme3.scene.Start;
import org.openide.loaders.DataObject;
import org.openide.nodes.Node;
import org.openide.nodes.Sheet;
import org.openide.util.lookup.ServiceProvider;

/** Scene Explorer properties for a camera start transform. */
@ServiceProvider(service = SceneExplorerNode.class)
public final class JmeStart extends JmeNode {
    private Start start;

    public JmeStart() {
    }

    private JmeStart(Start start, JmeSpatialChildren children) {
        super(start, children);
        this.start = start;
        refreshProperties();
    }

    @Override
    protected Sheet createSheet() {
        Sheet sheet = super.createSheet();
        if (start == null) {
            return sheet;
        }
        Sheet.Set properties = Sheet.createPropertiesSet();
        properties.setName("cameraStart");
        properties.setDisplayName("Start");
        Node.Property<?> priority = makeProperty(start, int.class,
                "getPriorityId", "setPriorityId", "Priority ID");
        priority.setShortDescription("Higher IDs win. Ties use the last Start in scene traversal order.");
        properties.put(priority);
        sheet.put(properties);
        return sheet;
    }

    @Override
    public Class<?> getExplorerObjectClass() {
        return Start.class;
    }

    @Override
    public Class<?> getExplorerNodeClass() {
        return JmeStart.class;
    }

    @Override
    public Node[] createNodes(Object key, DataObject file, boolean readOnly) {
        Start marker = (Start) key;
        JmeSpatialChildren children = new JmeSpatialChildren(marker);
        children.setReadOnly(readOnly);
        children.setDataObject(file);
        return new Node[]{new JmeStart(marker, children).setReadOnly(readOnly)};
    }
}
