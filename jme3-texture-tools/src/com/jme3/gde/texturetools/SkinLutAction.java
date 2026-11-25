package com.jme3.gde.texturetools;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JFrame;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;

/**
 * Opens the skin LUT and normal-prefilter tools. Compressed input decoding may use the bundled CPU codec; no renderer is started.
 * <pre>
 * 第一步：Tools → Textures → Pre-integrated Skin Tools，在 LUT 页选择或编辑扩散剖面。
 * 第二步：编辑 RGB 散射距离或高斯权重，点击 Generate 更新预览。
 * 第三步：保留 Browse，先选已打开的工程，再选工程纹理；也可从资产面板或资源管理器
 * 将 DDS/PNG/JPEG/TGA/BMP 等纹理拖到源输入框、按钮或预览区，设置密度和质量后生成。
 * 第四步：底部 Browse 选择已打开的工程（不会弹系统目录选择器），自动填入 Assets。
 * 输入输出资产键后 Export assets。LUT 使用线性 RGBA16F DDS，无 mip，无损保留半浮点。
 * 第四步补充：确认覆盖旧导出时迁移本工具生成的 Image J3O；无关同名文件拒绝覆盖。
 * 第五步：将生成的 .j3m 赋给模型，设置底色；SkinStrength 设为 0 可关闭散射。
 * 第六步：Load profile 可读取导出的参数文件。工具不会改写原图、模型或 Lightmap。
 * </pre>
 * Generation runs on a worker and can be cancelled by closing the window.
 * Export prepares a complete image/material/settings set before publication;
 * closing during publication lets that transaction finish or restore its files.
 */
@ActionID(category = "Textures", id = "com.jme3.gde.texturetools.SkinLutAction")
@ActionRegistration(displayName = "#CTL_SkinLut")
@ActionReference(path = "Menu/Tools/Textures", position = 120)
public final class SkinLutAction implements ActionListener {
    @Override
    public void actionPerformed(ActionEvent event) {
        JFrame window = new JFrame("Pre-integrated Skin Tools");
        SkinToolsPanel panel = new SkinToolsPanel();
        window.setContentPane(panel);
        window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                panel.close();
            }
        });
        window.setSize(1100, 780);
        window.setLocationByPlatform(true);
        window.setVisible(true);
    }
}
