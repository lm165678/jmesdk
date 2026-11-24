package com.jme3.gde.texturetools;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JFrame;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;

/**
 * Opens the pure-Java skin LUT and normal-prefilter tools. No renderer or native codec is started.
 * <pre>
 * 第一步：Tools → Textures → Pre-integrated Skin Tools，在 LUT 页选择或编辑扩散剖面。
 * 第二步：编辑 RGB 散射距离或高斯权重，点击 Generate 更新预览。
 * 第三步：法线页选择源图、源像素密度与质量，生成双法线近似或完整四法线贴图。
 * 第四步：选择项目 assets 根目录和输出资产键，点击 Export assets。
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
