package jada.ngeditor.controller.commands;

import jada.ngeditor.controller.Command;
import jada.ngeditor.controller.GUIEditor;
import jada.ngeditor.model.GUI;
import jada.ngeditor.model.elements.GElement;
import javax.swing.undo.AbstractUndoableEdit;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

/**
 * Attribute edit with model-based ID validation.
 * The legacy IDgenerator retains old IDs after an edit, which makes undoing an
 * ID rename fail. This override checks the live GUI tree and excludes the edited
 * element, so rename, undo and redo use the same rule.
 */
public class EditAttributeCommand extends AbstractUndoableEdit implements Command {
    private final GUIEditor editor;
    private final UndoManager manager;
    private GElement toBeEdited;
    private String attribute;
    private String value;
    private String oldValue;

    public EditAttributeCommand(GUIEditor editor, UndoManager manager) {
        this.editor = editor;
        this.manager = manager;
    }

    @Override
    public void perform() throws Exception {
        if (attribute == null || attribute.isEmpty() || value == null) {
            throw new IllegalStateException("Attribute and value are required");
        }
        if (toBeEdited == null) {
            toBeEdited = editor.getSelected();
        }
        if (toBeEdited == null) {
            throw new IllegalStateException("No Nifty element is selected");
        }
        oldValue = toBeEdited.getAttribute(attribute);
        apply(value);
    }

    private void apply(String newValue) throws Exception {
        if (!"id".equals(attribute)) {
            editor.getElementEditor(toBeEdited).setAttribute(attribute, newValue);
            return;
        }
        if (newValue == null || newValue.trim().isEmpty()) {
            throw new IllegalArgumentException("ID 不能为空。");
        }
        GUI gui = editor.getGui();
        if (gui != null && idInUse(gui, toBeEdited, newValue)) {
            throw new IllegalArgumentException("ID 已被其他组件使用: " + newValue);
        }
        String previous = toBeEdited.getAttribute(attribute);
        toBeEdited.addAttribute(attribute, newValue);
        try {
            toBeEdited.refresh();
        } catch (RuntimeException ex) {
            toBeEdited.addAttribute(attribute, previous);
            try {
                toBeEdited.refresh();
            } catch (RuntimeException refreshFailure) {
                ex.addSuppressed(refreshFailure);
            }
            throw ex;
        }
    }

    private static boolean idInUse(GUI gui, GElement edited, String id) {
        for (GElement screen : gui.getScreens()) {
            if (idInUse(screen, edited, id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean idInUse(GElement current, GElement edited, String id) {
        if (current != edited && id.equals(current.getID())) {
            return true;
        }
        for (GElement child : current.getElements()) {
            if (idInUse(child, edited, id)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void undo() throws CannotUndoException {
        super.undo();
        try {
            apply(oldValue == null ? "" : oldValue);
        } catch (Exception ex) {
            CannotUndoException failure = new CannotUndoException();
            failure.initCause(ex);
            throw failure;
        }
    }

    @Override
    public void redo() throws CannotRedoException {
        super.redo();
        try {
            apply(value);
        } catch (Exception ex) {
            CannotRedoException failure = new CannotRedoException();
            failure.initCause(ex);
            throw failure;
        }
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Edit Element";
    }

    public void setElement(GElement element) {
        this.toBeEdited = element;
    }

    public void setAttribute(String attribute) {
        this.attribute = attribute;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
