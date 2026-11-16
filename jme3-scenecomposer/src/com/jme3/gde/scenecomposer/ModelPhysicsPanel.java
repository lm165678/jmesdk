package com.jme3.gde.scenecomposer;

import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.collision.shapes.CollisionShape;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.bullet.control.PhysicsControl;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.bullet.util.CollisionShapeFactory;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.undoredo.AbstractUndoableSceneEdit;
import com.jme3.gde.core.undoredo.SceneUndoRedoManager;
import com.jme3.scene.Spatial;
import java.awt.BorderLayout;
import javax.swing.*;
import org.openide.util.Lookup;

/**
 * Collision inspector for actual scene geometry. An inherited body's owner is
 * shown explicitly. Rebuild replaces the shape on the same control and keeps
 * its friction, restitution, groups, enabled flag and physics-space membership.
 * Minie characters expose their collision shape through getCharacter();
 * rebuilding keeps that physics character and its movement parameters.
 * Changing between rigid body and character replaces the control. Both actions
 * are render-thread transactions with undo; generated shapes are not disposed
 * while referenced by undo history. Custom physics controls are left untouched.
 * Static mesh remains the default when no physics control exists. Box/Sphere
 * enclose all descendant mesh bounds, including their offsets from the pivot.
 * A simple shape with mass zero is static; positive mass creates a dynamic body.
 * <pre>
 * 第一步：选中 Geometry 或包含模型的 Node，打开 Properties 的 Physics 页。
 * 第二步：默认 Static mesh；需要简单碰撞时选择 Box 或 Sphere。
 * 第三步：简单形体由模型包围范围生成，Mass 为 0 时静态，大于 0 时动态。
 * 第四步：点击 Rebuild collision，Ctrl+Z 撤销，Ctrl+S 保存。
 * </pre>
 */
final class ModelPhysicsPanel extends JPanel {
    private final JLabel ownerLabel = new JLabel(" ");
    private final JComboBox<String> type = new JComboBox<>(new String[]{"Static mesh / Terrain", "Dynamic hull", "Character capsule",
            com.jme3.gde.core.util.EditorMessages.text("CollisionBox"),
            com.jme3.gde.core.util.EditorMessages.text("CollisionSphere")});
    private final JSpinner mass = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 1000000.0, 1.0));
    private final JSpinner radius = new JSpinner(new SpinnerNumberModel(.5, .001, 100000.0, .05));
    private final JSpinner height = new JSpinner(new SpinnerNumberModel(1.0, .001, 100000.0, .1));
    private final JButton rebuild = new JButton("Rebuild collision");
    private final JLabel status = new JLabel(" ");
    private volatile Spatial selected;
    private SceneEditorController editor;

    ModelPhysicsPanel() {
        super(new BorderLayout(4, 4));
        JPanel rows = new com.jme3.gde.core.util.InspectorColumns("model.physics");
        rows.add(new JLabel("Collision owner"));
        rows.add(ownerLabel);
        rows.add(new JLabel("Type"));
        rows.add(type);
        rows.add(new JLabel("Mass (dynamic)"));
        rows.add(mass);
        rows.add(new JLabel("Capsule radius"));
        rows.add(radius);
        rows.add(new JLabel("Cylinder height"));
        rows.add(height);
        JPanel content = new JPanel(new BorderLayout(4, 8));
        content.add(rows, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(rebuild, BorderLayout.NORTH);
        buttons.add(status, BorderLayout.SOUTH);
        content.add(buttons, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(content, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setMinimumSize(new java.awt.Dimension(0, 0));
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
        rebuild.setToolTipText("Create or rebuild the collision Control on the displayed owner. Undo is supported.");
        type.setToolTipText(com.jme3.gde.core.util.EditorMessages.text("CollisionTypeTip"));
        mass.setToolTipText(com.jme3.gde.core.util.EditorMessages.text("CollisionMassTip"));
        type.addActionListener(event -> enableParameters());
        rebuild.addActionListener(event -> rebuild());
        enableParameters();
    }

    private void enableParameters() {
        mass.setEnabled(type.getSelectedIndex() == 1 || type.getSelectedIndex() >= 3);
        radius.setEnabled(type.getSelectedIndex() == 2);
        height.setEnabled(type.getSelectedIndex() == 2);
    }

    void showSelection(Spatial target, SceneEditorController controller) {
        selected = target;
        editor = controller;
        rebuild.setEnabled(false);
        if (target == null) {
            return;
        }
        SceneApplication.getApplication().enqueue(() -> {
            Spatial owner = owner(target);
            PhysicsControl existing = control(owner);
            boolean supported = existing == null || existing instanceof RigidBodyControl || existing instanceof CharacterControl;
            float valueMass = existing instanceof RigidBodyControl body ? body.getMass() : 0;
            CollisionShape shape = shape(existing);
            int simpleType = ModelCollisionShapes.simpleType(shape);
            SwingUtilities.invokeLater(() -> {
                if (selected != target) {
                    return;
                }
                ownerLabel.setText(owner.getName());
                ownerLabel.setToolTipText(owner == target ? "Selected model" : "Inherited collision: rebuilding affects the complete parent model");
                type.setSelectedIndex(existing instanceof CharacterControl ? 2 : simpleType >= 0 ? simpleType : valueMass > 0 ? 1 : 0);
                mass.setValue((double) valueMass);
                if (shape instanceof CapsuleCollisionShape capsule) {
                    radius.setValue((double) capsule.getRadius());
                    height.setValue((double) Math.max(.001f, capsule.getHeight()));
                }
                rebuild.setEnabled(supported && editor != null);
                status.setText(supported ? "Rebuild preserves existing physics settings." : "Edit this custom control in Scene Explorer.");
                status.setToolTipText(status.getText());
                enableParameters();
            });
            return null;
        });
    }

    private static PhysicsControl control(Spatial target) {
        for (int i = 0; i < target.getNumControls(); i++) {
            if (target.getControl(i) instanceof PhysicsControl physics) {
                return physics;
            }
        }
        return null;
    }

    private static Spatial owner(Spatial target) {
        var request = SceneApplication.getApplication().getCurrentSceneRequest();
        for (Spatial current = target; current != null; current = current.getParent()) {
            if (control(current) != null) {
                return current;
            }
            if (request != null && current == request.getRootNode()) {
                break;
            }
        }
        return target;
    }

    private static CollisionShape shape(PhysicsControl control) {
        if (control instanceof RigidBodyControl body) {
            return body.getCollisionShape();
        }
        if (control instanceof CharacterControl character) {
            return character.getCharacter().getCollisionShape();
        }
        return null;
    }

    private void rebuild() {
        Spatial target = selected;
        SceneEditorController controller = editor;
        int mode = type.getSelectedIndex();
        float nextMass = mode == 1 || mode >= 3 ? ((Number) mass.getValue()).floatValue() : 0;
        float r = ((Number) radius.getValue()).floatValue();
        float h = ((Number) height.getValue()).floatValue();
        if (target == null || controller == null) {
            return;
        }
        rebuild.setEnabled(false);
        SceneApplication.getApplication().enqueue(() -> {
            try {
                if (selected != target || !ModelBakeSupport.isEditableSceneModel(target)) {
                    throw new IllegalStateException("Select an editable scene model.");
                }
                Spatial owner = owner(target);
                PhysicsControl before = control(owner);
                if (before != null && !(before instanceof RigidBodyControl) && !(before instanceof CharacterControl)) {
                    throw new IllegalStateException("Custom physics controls must be edited explicitly.");
                }
                if (before == null) {
                    boolean[] descendantBody = {false};
                    owner.depthFirstTraversal(child -> {
                        if (child != owner && control(child) != null) {
                            descendantBody[0] = true;
                        }
                    });
                    if (descendantBody[0]) {
                        throw new IllegalStateException("Select the child owning the collision control.");
                    }
                }
                Spatial updateRoot = owner;
                while (updateRoot.getParent() != null) {
                    updateRoot = updateRoot.getParent();
                }
                updateRoot.updateGeometricState();
                CollisionShape afterShape = switch (mode) {
                    case 1 -> CollisionShapeFactory.createDynamicMeshShape(owner);
                    case 2 -> new CapsuleCollisionShape(r, h);
                    case 3 -> ModelCollisionShapes.create(owner, false);
                    case 4 -> ModelCollisionShapes.create(owner, true);
                    default -> CollisionShapeFactory.createMeshShape(owner);
                };
                CollisionShape beforeShape = shape(before);
                float beforeMass = before instanceof RigidBodyControl body ? body.getMass() : 0;
                PhysicsControl after = mode == 2
                        ? before instanceof CharacterControl ? before : new CharacterControl((CapsuleCollisionShape) afterShape, r * .2f)
                        : before instanceof RigidBodyControl ? before : new RigidBodyControl(afterShape, nextMass);
                if (before != null && after != before) {
                    after.setEnabled(before.isEnabled());
                }
                Runnable redo = () -> {
                    swap(owner, before, after, afterShape, nextMass);
                    controller.refreshCollisionOwner(owner);
                };
                Runnable undo = () -> {
                    swap(owner, after, before, beforeShape, beforeMass);
                    controller.refreshCollisionOwner(owner);
                };
                redo.run();
                controller.setNeedsSave(true);
                Lookup.getDefault().lookup(SceneUndoRedoManager.class).addEdit(this, new AbstractUndoableSceneEdit() {
                    @Override public void sceneUndo() { undo.run(); controller.setNeedsSave(true); }
                    @Override public void sceneRedo() { redo.run(); controller.setNeedsSave(true); }
                });
                SwingUtilities.invokeLater(() -> {
                    status.setText("Collision rebuilt. Ctrl+Z to undo; Ctrl+S to save.");
                    status.setToolTipText(status.getText());
                    rebuild.setEnabled(selected == target);
                });
            } catch (RuntimeException error) {
                SwingUtilities.invokeLater(() -> {
                    status.setText(error.getMessage());
                    status.setToolTipText(error.toString());
                    rebuild.setEnabled(selected == target);
                });
            }
            return null;
        });
    }

    private static void swap(Spatial owner, PhysicsControl from, PhysicsControl to, CollisionShape shape, float mass) {
        var space = from == null ? null : from.getPhysicsSpace();
        CollisionShape originalShape = shape(from);
        float originalMass = from instanceof RigidBodyControl body ? body.getMass() : 0;
        try {
            if (from != null) {
                from.setPhysicsSpace(null);
            }
            if (from != to && from != null) {
                owner.removeControl(from);
            }
            if (to != null) {
                if (to instanceof RigidBodyControl body) {
                    // Static triangle meshes reject a nonzero mass during shape assignment.
                    body.setMass(0);
                    body.setCollisionShape(shape);
                    body.setMass(mass);
                } else if (to instanceof CharacterControl character) {
                    character.getCharacter().setCollisionShape(shape);
                }
                if (from != to) {
                    owner.addControl(to);
                }
                to.setPhysicsSpace(space);
            }
        } catch (RuntimeException error) {
            if (to != null) {
                to.setPhysicsSpace(null);
            }
            if (from != to && to != null) {
                owner.removeControl(to);
            }
            if (from instanceof RigidBodyControl body) {
                body.setMass(0);
                body.setCollisionShape(originalShape);
                body.setMass(originalMass);
            } else if (from instanceof CharacterControl character) {
                character.getCharacter().setCollisionShape(originalShape);
            }
            if (from != null) {
                if (from != to) {
                    owner.addControl(from);
                }
                from.setPhysicsSpace(space);
            }
            throw error;
        }
    }
}
