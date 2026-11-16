package com.jme3.gde.gui.nodes;

import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.undo.AbstractUndoableEdit;
import javax.swing.undo.UndoManager;
import jada.ngeditor.model.GUI;

/** Registers reference changes in the owning visual editor's undo history. */
public final class ReferenceEdit extends AbstractUndoableEdit {
    private static final Map<GUI, UndoManager> MANAGERS = new WeakHashMap<>();
    private final Runnable apply;
    private final Runnable revert;

    private ReferenceEdit(Runnable apply, Runnable revert) {
        this.apply = apply;
        this.revert = revert;
    }

    public static void bind(GUI gui, UndoManager manager) {
        MANAGERS.put(gui, manager);
    }

    public static void unbind(GUI gui) {
        MANAGERS.remove(gui);
    }

    /** Execute on the EDT; failed changes are never added to the history. */
    public static void perform(GUI gui, Runnable apply, Runnable revert) {
        apply.run();
        UndoManager manager = MANAGERS.get(gui);
        if (manager != null) {
            manager.addEdit(new ReferenceEdit(apply, revert));
        }
    }

    @Override
    public void undo() {
        super.undo();
        revert.run();
    }

    @Override
    public void redo() {
        super.redo();
        apply.run();
    }
}
