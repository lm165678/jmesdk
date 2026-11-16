package com.jme3.gde.core.assets;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Editor-only annotation metadata stored as portable String user data beside the
 * runtime graph. Missing metadata means no comments; unknown versions fail without
 * replacing existing data. Immutable boxes can be shared by undo snapshots.
 * <pre>
 * 第一步：在 State graph 空白处拖出选择框，框选多个状态，按 C 创建备注框。
 * 第二步：双击备注标题编辑多行文字；拖标题移动框内状态，拖右下角调整大小。
 * 第三步：Save J3O 保存；删除备注不删除状态，游戏端忽略此编辑器元数据。
 * </pre>
 */
final class AnimationGraphComments {
    static final String USER_DATA = "jme.sdk.animationGraph.comments.v1";

    record Box(String text, int x, int y, int width, int height) {
        Box {
            if (text == null || x < 0 || y < 0 || width < 100 || height < 80) {
                throw new IllegalArgumentException("Invalid animation comment bounds/text");
            }
        }

        Rectangle bounds() {
            return new Rectangle(x, y, width, height);
        }
    }

    static String encode(List<Box> boxes) {
        Properties p = new Properties();
        p.setProperty("version", "1");
        p.setProperty("count", Integer.toString(boxes.size()));
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            String key = "box." + i + ".";
            p.setProperty(key + "text", box.text());
            p.setProperty(key + "x", Integer.toString(box.x()));
            p.setProperty(key + "y", Integer.toString(box.y()));
            p.setProperty(key + "w", Integer.toString(box.width()));
            p.setProperty(key + "h", Integer.toString(box.height()));
        }
        try {
            StringWriter writer = new StringWriter();
            p.store(writer, null);
            return writer.toString().replaceAll("(?m)^#.*\\r?\\n", "");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static List<Box> decode(String text) {
        List<Box> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception);
        }
        if (!"1".equals(p.getProperty("version"))) {
            throw new IllegalArgumentException("Unsupported animation comment version");
        }
        int count = Integer.parseInt(p.getProperty("count"));
        if (count < 0 || count > 10000) {
            throw new IllegalArgumentException("Invalid comment count");
        }
        for (int i = 0; i < count; i++) {
            String key = "box." + i + ".";
            result.add(new Box(p.getProperty(key + "text"), Integer.parseInt(p.getProperty(key + "x")),
                    Integer.parseInt(p.getProperty(key + "y")), Integer.parseInt(p.getProperty(key + "w")),
                    Integer.parseInt(p.getProperty(key + "h"))));
        }
        return result;
    }
}
