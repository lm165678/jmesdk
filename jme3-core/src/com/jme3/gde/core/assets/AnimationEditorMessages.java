package com.jme3.gde.core.assets;

import java.awt.Component;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.function.Predicate;
import org.openide.util.NbBundle;
import org.openide.util.Lookup;

/**
 * Follows installed SDK window-system translations, not merely the OS locale.
 * English-only SDK distributions remain English on Chinese Windows. Bundle loading
 * disables JVM-default-locale fallback so English cannot fall back to OS Chinese.
 * 第一步：通过 SDK 的语言设置选择语言并按要求重启；第二步：打开动画编辑器。
 * 界面与教程采用 SDK 已安装的界面语言；缺少语言包时回退英文。
 * 用户命名和保存的类型标识保持原样；不修改 JVM 全局语言或区域设置。
 */
final class AnimationEditorMessages {
    private AnimationEditorMessages() {
    }

    static String text(String key) {
        return bundle().getString(key);
    }

    private static ResourceBundle bundle() {
        ClassLoader platform = Lookup.getDefault().lookup(ClassLoader.class);
        if (platform == null) {
            platform = AnimationEditorMessages.class.getClassLoader();
        }
        ClassLoader loader = platform;
        Locale locale = uiLocale(Locale.getDefault(), NbBundle.getBranding(),
                path -> loader.getResource(path) != null);
        return messages(locale);
    }

    static ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle("com.jme3.gde.core.assets.AnimationEditorMessages", locale,
                AnimationEditorMessages.class.getClassLoader(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    static Locale uiLocale(Locale requested, String branding, Predicate<String> resourceExists) {
        String base = "org/netbeans/core/windows/view/ui/Bundle";
        for (Locale candidate : ResourceBundle.Control.getControl(ResourceBundle.Control.FORMAT_PROPERTIES)
                .getCandidateLocales("SDK", requested)) {
            if (candidate.equals(Locale.ROOT) || candidate.getLanguage().equals("en")) {
                return Locale.ENGLISH;
            }
            String suffix = "_" + candidate + ".properties";
            if (resourceExists.test(base + suffix)) {
                return requested;
            }
            String brand = branding;
            while (brand != null && !brand.isEmpty()) {
                if (resourceExists.test(base + "_" + brand + suffix)) {
                    return requested;
                }
                int split = brand.lastIndexOf('_');
                brand = split < 0 ? null : brand.substring(0, split);
            }
        }
        return Locale.ENGLISH;
    }

    static String value(Object value) {
        if (value == null) {
            return "";
        }
        String key = "Animation.value." + value;
        var bundle = bundle();
        return bundle.containsKey(key) ? bundle.getString(key) : value.toString();
    }

    static DefaultListCellRenderer valueRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object item, int index,
                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, item, index, selected, focused);
                setText(value(item));
                return this;
            }
        };
    }

    static String failure(Throwable exception) {
        String message = exception.getMessage();
        if (message == null) {
            return exception.getClass().getSimpleName();
        }
        int separator = message.indexOf(':');
        String prefix = separator < 0 ? message : message.substring(0, separator);
        String key = "Animation.error." + prefix.replaceAll("[^A-Za-z0-9]+", "_");
        var bundle = bundle();
        if (bundle.containsKey(key)) {
            return bundle.getString(key) + (separator < 0 ? "" : message.substring(separator));
        }
        return message;
    }


}
