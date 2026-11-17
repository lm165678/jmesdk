/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jme3.gde.gui.nodes;

import com.jme3.gde.gui.propertyeditors.ResourceEditor;
import com.jme3.gde.gui.propertyeditors.SizeEditor;
import jada.ngeditor.controller.CommandProcessor;
import jada.ngeditor.controller.commands.EditAttributeCommand;
import jada.ngeditor.controller.commands.VisibilityCommand;
import jada.ngeditor.model.elements.GElement;
import java.awt.event.ActionEvent;
import java.beans.PropertyEditor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map.Entry;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JOptionPane;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.Children;
import org.openide.nodes.Node;
import org.openide.nodes.Sheet;

/**
 *
 * @author cris
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class GElementNode extends AbstractNode implements java.util.Observer{
    private static final java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger(GElementNode.class.getName());
    private final GElement element;
    private final Runnable afterEdit;
    private static final String basePath="com/jme3/gde/gui/multiview/icons";
    public GElementNode(GElement element) {
        this(element, null);
    }

    /**
     * Creates a property node with a callback that restores/synchronizes the
     * visual editor after both successful and rejected property edits.
     */
    public GElementNode(GElement element, Runnable afterEdit) {
        super(Children.create(new GElementChildFactory(element), false));

        this.element = element;
        this.afterEdit = afterEdit;
        this.element.addObserver(this);
        this.setName(element.getID());
        String name = this.element.getClass().getSimpleName();
        this.setDisplayName(name + " (" + element.getID() + ")");
        this.setIconBaseWithExtension(basePath+"/"+name+".png");

    }
    
    public GElement getGelement(){
        return element;
    }

    @Override
    public void update(java.util.Observable source, Object event) {
        refreshLabel();
    }

    private void refreshLabel() {
        String oldName = getName();
        String oldDisplayName = getDisplayName();
        String newName = element.getID();
        String newDisplayName = element.getClass().getSimpleName() + " (" + newName + ")";
        if (!java.util.Objects.equals(oldName, newName)) {
            setName(newName);
            fireNameChange(oldName, newName);
        }
        if (!java.util.Objects.equals(oldDisplayName, newDisplayName)) {
            setDisplayName(newDisplayName);
            fireDisplayNameChange(oldDisplayName, newDisplayName);
        }
    }

    @Override
    public Action[] getActions(boolean context) {
        if(!context){
            return new Action[]{new Visibility("Show", true),new Visibility("Hide", false)};
        }else
           return super.getActions(context);
    }
    
    public void updateChildren(){
        
    }
    @Override
    protected Sheet createSheet() {
        Sheet s = super.createSheet();
        Sheet.Set set = s.get(Sheet.PROPERTIES);
        if (set == null) {
            set = Sheet.createPropertiesSet();
            s.put(set);
        }
        set.setName("Element Properties");
        set.setShortDescription("Choose layout/alignment/visibility values; sizes accept px, %, %w, %h or *");
        java.util.Map<String, String> attributes =
                new java.util.LinkedHashMap<>(this.element.listAttributes());
        if (!(element instanceof jada.ngeditor.model.elements.GScreen)) {
            attributes.putIfAbsent("width", "");
            attributes.putIfAbsent("height", "");
            attributes.putIfAbsent("x", "");
            attributes.putIfAbsent("y", "");
            attributes.putIfAbsent("align", "");
            attributes.putIfAbsent("valign", "");
            attributes.putIfAbsent("childLayout", "");
            attributes.putIfAbsent("visibleToMouse", "");
        }
        for (Entry<String, String> pair : attributes.entrySet()) {
            final ElementAttributeProperty elementAttributeProperty = new ElementAttributeProperty(element, pair.getKey());
            pickEditor(pair, elementAttributeProperty);
            set.put(elementAttributeProperty);
        }
        s.put(set);
        return s;
    }

    private void pickEditor(Entry<String, String> pair, final ElementAttributeProperty elementAttributeProperty) {
        String name = pair.getKey();
        if (isSizeAttribute(name)) {
            elementAttributeProperty.setPropertyEditor(new SizeEditor());
        } else if (name.equals("filename") || name.equals("backgroundImage") || name.equals("font")) {
            elementAttributeProperty.setPropertyEditor(new ResourceEditor());
        } else if (choicesFor(name) != null) {
            elementAttributeProperty.setPropertyEditor(new ChoiceEditor(choicesFor(name)));
        }
    }

    private static String[] choicesFor(String name) {
        switch (name) {
            case "childLayout":
                return new String[]{"absolute", "absolute-inside", "center", "vertical", "horizontal", "overlay"};
            case "align":
                return new String[]{"left", "center", "right"};
            case "valign":
                return new String[]{"top", "center", "bottom"};
            default:
                break;
        }
        if (isBooleanAttribute(name)) {
            return new String[]{"true", "false"};
        }
        return null;
    }

    private static boolean isBooleanAttribute(String name) {
        return java.util.Set.of("visible", "visibleToMouse", "enabled", "focusable",
                "childClip", "inherit", "post", "wrap").contains(name);
    }

    private static boolean isSizeAttribute(String name) {
        return java.util.Set.of("width", "height", "x", "y").contains(name)
                || name.startsWith("padding") || name.startsWith("margin");
    }

    private static String normalizeValue(String name, String value) {
        String trimmed = value.trim();
        if (isSizeAttribute(name)) {
            return normalizeSize(trimmed);
        }
        if (name.equalsIgnoreCase("color") || name.toLowerCase().endsWith("color")) {
            return normalizeColor(trimmed);
        }
        return trimmed;
    }

    private static String normalizeSize(String value) {
        if (value.isEmpty() || value.equals("default") || value.equals("sum")
                || value.equals("max") || value.equals("*")) {
            return value;
        }
        String normalized = value;
        if (normalized.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")) {
            normalized += "px";
        }
        try {
            new de.lessvoid.nifty.tools.SizeValue(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "尺寸格式无效。可输入 100、100px、35%、35%w、35%h、*、sum、max 或 default。", ex);
        }
    }

    private static String normalizeColor(String value) {
        if (value.isEmpty() || value.matches("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) {
            return value;
        }
        throw new IllegalArgumentException("颜色格式应为 #rgb、#rgba、#rrggbb 或 #rrggbbaa。");
    }

    private static final class ChoiceEditor extends java.beans.PropertyEditorSupport {
        private final String[] choices;

        private ChoiceEditor(String[] choices) {
            this.choices = choices;
        }

        @Override
        public String[] getTags() {
            return choices.clone();
        }

        @Override
        public void setAsText(String text) throws IllegalArgumentException {
            if (!java.util.Arrays.asList(choices).contains(text)) {
                throw new IllegalArgumentException("请选择 " + String.join(", ", choices));
            }
            super.setAsText(text);
        }
    }
    
    public  class ElementAttributeProperty extends Node.Property {

        private String attributeName;
        private GElement element;
        private PropertyEditor editor;

        public ElementAttributeProperty(GElement element, String attributeName) {
            super(String.class);
            this.element = element;
            this.attributeName = attributeName;
            this.setName(attributeName);
            this.setDisplayName(attributeName);
           
        }

        @Override
        public boolean canRead() {
            return true;
        }

        @Override
        public String getValue() throws IllegalAccessException, InvocationTargetException{
            return element.getAttribute(attributeName);
        }

        @Override
        public boolean canWrite() {
            return true;
        }
        
        public void setPropertyEditor(PropertyEditor editor){
            this.editor = editor;
        }
        @Override
        public PropertyEditor getPropertyEditor() {  
            
            return this.editor; //To change body of generated methods, choose Tools | Templates.
        }
        
        

        @Override
        public void setValue(Object val) throws IllegalAccessException, IllegalArgumentException {
            if (val == null) {
                return;
            }
            String newValue;
            try {
                newValue = normalizeValue(attributeName, val.toString());
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(null, ex.getMessage(),
                        "Invalid Nifty property", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (java.util.Objects.equals(element.getAttribute(attributeName), newValue)) {
                return;
            }
            try {
                EditAttributeCommand command = CommandProcessor.getInstance().getCommand(EditAttributeCommand.class);
                command.setElement(element);
                command.setAttribute(attributeName);
                command.setValue(newValue);
                CommandProcessor.getInstance().excuteCommand(command);
                if (afterEdit != null) {
                    afterEdit.run();
                }
            } catch (Exception ex) {
                try {
                    // ElementEditor has restored the old attribute; rebuild the
                    // preview as well so an invalid edit cannot strand rendering.
                    element.refresh();
                } catch (RuntimeException refreshFailure) {
                    ex.addSuppressed(refreshFailure);
                }
                if (afterEdit != null) {
                    afterEdit.run();
                }
                logger.log(java.util.logging.Level.WARNING,
                        "Cannot edit Nifty attribute " + attributeName, ex);
                JOptionPane.showMessageDialog(null,
                        "Cannot apply " + attributeName + ": " + ex.getMessage(),
                        "Invalid Nifty property", JOptionPane.WARNING_MESSAGE);
            }

        }
    };
    
    private  class Visibility extends AbstractAction {
        private final boolean param;

        public Visibility(String name ,boolean param) {
            super(name);
            this.param = param;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            VisibilityCommand command = CommandProcessor.getInstance().getCommand(VisibilityCommand.class);
            CommandProcessor.getInstance().getGuiEditor().selectElement(element);
            command.setVisibility(param);
            try {
                CommandProcessor.getInstance().excuteCommand(command);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, ex.getMessage());
            }
        }
    }
}
