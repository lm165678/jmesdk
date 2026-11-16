package com.jme3.gde.core.util;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import javax.swing.*;
import org.openide.util.NbPreferences;

/**
 * Shared free FlatLaf theme and logical-pixel editor spacing. Runs on Swing EDT.
 * Dark is the default; the preview options menu persists Dark/Light. UI scale
 * follows the operating system, never overrides sun.java2d.uiScale.
 * <pre>
 * 第一步：启动 SDK，默认使用 FlatLaf 深色主题。
 * 第二步：预览工具栏 ⋯ 菜单选择 Dark / Light，立即生效并记住选择。
 * 第三步：系统缩放保持 125% 等原值；不要用禁用 DPI 缩放掩盖画布问题。
 * </pre>
 */
public final class EditorUiStyle {
    static {
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource(
                "com.jme3.gde.core.util.theme", EditorUiStyle.class.getClassLoader());
    }
    private EditorUiStyle() {
    }

    public static String theme() {
        return NbPreferences.forModule(EditorUiStyle.class).get("editorTheme", "Dark");
    }

    public static void install() {
        SwingUtilities.invokeLater(() -> setTheme(theme()));
        org.openide.windows.WindowManager.getDefault().invokeWhenUIReady(() -> {
            for (Window window : Window.getWindows()) {
                normalize(window);
            }
        });
    }

    public static void setTheme(String name) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setTheme(name));
            return;
        }
        // Remove application overrides before installing another palette, otherwise
        // dark input backgrounds survive a switch to the light look and feel.
        for (String key : new String[]{"Panel.background", "ToolBar.background", "Viewport.background",
                "TextField.background", "ComboBox.background", "Spinner.background",
                "ToggleButton.selectedBackground", "Tree.selectionBackground", "Table.selectionBackground",
                "List.selectionBackground", "Component.accentColor", "TitledBorder.titleColor"}) {
            UIManager.put(key, null);
        }
        boolean success = "Light".equals(name) ? FlatLightLaf.setup() : FlatDarkLaf.setup();
        if (success) {
            NbPreferences.forModule(EditorUiStyle.class).put("editorTheme", name);
            if (!"Light".equals(name)) {
                java.awt.Color panel = new java.awt.Color(35, 38, 43);
                java.awt.Color field = new java.awt.Color(28, 31, 36);
                java.awt.Color accent = new java.awt.Color(48, 109, 174);
                for (String key : new String[]{"Panel.background", "ToolBar.background", "Viewport.background"}) {
                    UIManager.put(key, panel);
                }
                for (String key : new String[]{"TextField.background", "ComboBox.background", "Spinner.background"}) {
                    UIManager.put(key, field);
                }
                for (String key : new String[]{"ToggleButton.selectedBackground", "Tree.selectionBackground",
                        "Table.selectionBackground", "List.selectionBackground", "Component.accentColor"}) {
                    UIManager.put(key, accent);
                }
                UIManager.put("TitledBorder.titleColor", new java.awt.Color(184, 199, 218));
            }
            UIManager.put("Button.arc", 4);
            UIManager.put("Component.arc", 6);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("Table.rowHeight", 22);
            UIManager.put("Tree.rowHeight", 22);
            UIManager.put("ScrollBar.width", 10);
            for (Window window : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(window);
                normalize(window);
            }
        }
    }

    private static void normalize(Container container) {
        if (container instanceof org.openide.awt.Toolbar) {
            for (Component child : container.getComponents()) {
                if (child instanceof AbstractButton button) {
                    compactIcons(button);
                    button.setMargin(new Insets(3, 5, 3, 5));
                }
            }
        }
        if (container instanceof JToolBar bar) {
            toolbar(bar);
        }
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                normalize(nested);
            }
        }
    }

    /** Neutral, DPI-aware toolbar icons preserve each command's original silhouette. */
    private static Icon scaled(Icon original) {
        if (original == null || original instanceof CompactIcon) {
            return original;
        }
        return new CompactIcon(original);
    }

    private static final class CompactIcon implements Icon {
        private final Icon original;
        private java.awt.image.BufferedImage image;
        private int cachedSize;
        private int cachedColor;

        CompactIcon(Icon original) {
            this.original = original;
        }
        public int getIconWidth() {
            return 16;
        }
        public int getIconHeight() {
            return 16;
        }
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D output = (Graphics2D) graphics;
            int size = Math.max(16, (int) Math.ceil(16 * output.getTransform().getScaleX()));
            Color color = UIManager.getColor(component.isEnabled() ? "Label.foreground" : "Label.disabledForeground");
            if (color == null) {
                color = Color.GRAY;
            }
            if (image == null || cachedSize != size || cachedColor != color.getRGB()) {
                image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D painter = image.createGraphics();
                painter.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                painter.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                double ratio = (double) size / Math.max(original.getIconWidth(), original.getIconHeight());
                painter.translate((size - original.getIconWidth() * ratio) / 2,
                        (size - original.getIconHeight() * ratio) / 2);
                painter.scale(ratio, ratio);
                original.paintIcon(component, painter, 0, 0);
                painter.dispose();
                for (int py = 0; py < size; py++) {
                    for (int px = 0; px < size; px++) {
                        int argb = image.getRGB(px, py);
                        // Retain luminance detail instead of flattening solid image icons.
                        int light = (((argb >> 16) & 255) * 3 + ((argb >> 8) & 255) * 6 + (argb & 255)) / 10;
                        float amount = .55f + light / 566f;
                        int rgb = ((int) (color.getRed() * amount) << 16)
                                | ((int) (color.getGreen() * amount) << 8)
                                | (int) (color.getBlue() * amount);
                        image.setRGB(px, py, (argb & 0xff000000) | rgb);
                    }
                }
                cachedSize = size;
                cachedColor = color.getRGB();
            }
            output.drawImage(image, x, y, 16, 16, null);
        }
    }

    private static void compactIcons(AbstractButton button) {
        // NetBeans dropdowns decorate icons in setIcon; wrapping their decorated
        // result recursively would duplicate arrows. Keep their native renderer.
        if (button.getClass().getName().contains("DropDown")
                || Boolean.TRUE.equals(button.getClientProperty("sdk.updatingIcon"))) {
            return;
        }
        button.putClientProperty("sdk.updatingIcon", true);
        try {
            button.setIcon(scaled(button.getIcon()));
            button.setSelectedIcon(scaled(button.getSelectedIcon()));
            button.setRolloverIcon(scaled(button.getRolloverIcon()));
        } finally {
            button.putClientProperty("sdk.updatingIcon", false);
        }
        if (button.getClientProperty("sdk.compactIcons") == null) {
            button.putClientProperty("sdk.compactIcons", true);
            button.addPropertyChangeListener(event -> {
                if (("icon".equals(event.getPropertyName()) || "selectedIcon".equals(event.getPropertyName())
                        || "rolloverIcon".equals(event.getPropertyName()))
                        && event.getNewValue() instanceof Icon icon && !(icon instanceof CompactIcon)) {
                    compactIcons(button);
                }
            });
        }
    }

    public static void toolbar(JToolBar toolbar) {
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        toolbar.setMinimumSize(new Dimension(0, 30));
        toolbar.setPreferredSize(null);
        for (Component component : toolbar.getComponents()) {
            if (component instanceof AbstractButton button) {
                button.setFocusable(false);
                compactIcons(button);
                button.setMargin(new Insets(3, 5, 3, 5));
                button.setPreferredSize(null);
                Dimension preferred = button.getPreferredSize();
                Dimension size = new Dimension(Math.max(26, preferred.width), 26);
                button.setMinimumSize(size);
                button.setPreferredSize(size);
                button.setMaximumSize(size);
            }
        }
    }
}
