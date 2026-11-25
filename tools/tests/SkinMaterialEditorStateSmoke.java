package com.jme3.gde.texturetools;

import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.materials.EditableMaterialFile;
import javax.swing.SwingUtilities;
import org.openide.filesystems.FileUtil;

/** Parses generated bindings plus unsaved editor fields without saving the J3M. */
public final class SkinMaterialEditorStateSmoke {
    public static void main(String[] args) throws Exception {
        var root = FileUtil.createMemoryFileSystem().getRoot();
        var material = FileUtil.createData(root, "Target.j3m");
        var definition = FileUtil.createData(root, "Common/MatDefs/Light/PreintegratedSkin.j3md");
        try (var input = SkinMaterialEditorStateSmoke.class.getClassLoader().getResourceAsStream(
                "Common/MatDefs/Light/PreintegratedSkin.j3md"); var output = definition.getOutputStream()) {
            input.transferTo(output);
        }
        String previous = "Material Skin : Common/MatDefs/Light/PreintegratedSkin.j3md {\n"
                + "    MaterialParameters {\n        Roughness : 0.45\n    }\n}\n";
        String generated = previous.replace("Roughness : 0.45", "SkinThickness : 20\n"
                + "        SkinCurvatureMap : MinTrilinear \"curve.dds\"\n"
                + "        SkinThicknessMap : MinTrilinear \"thick.dds\"\n"
                + "        SkinTransmission : true\n        SkinWorldToMillimeters : 10");
        try (var output = material.getOutputStream()) {
            output.write(generated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        SwingUtilities.invokeAndWait(() -> {
            try {
                ProjectAssetManager manager = new ProjectAssetManager(root);
                EditableMaterialFile editable = new EditableMaterialFile(material, manager);
                String editor = SkinMaterialUpdates.merge(previous.replace("Roughness : 0.45", "Roughness : 0.73"), generated);
                editable.readText(editor);
                if (editable.getParameterMap().get("Roughness") == null) {
                    throw new AssertionError("Definition=" + editable.getMatDefName() + "; keys="
                            + editable.getParameterMap().keySet() + "; root=" + manager.getAssetFolder().getPath()
                            + "; localDef=" + definition.getSize());
                }
                if (!"0.73".equals(editable.getParameterMap().get("Roughness").getValue())
                        || !editable.getParameterMap().get("SkinThicknessMap").getValue().contains("thick.dds")
                        || !editable.getParameterMap().get("SkinCurvatureMap").getValue().contains("curve.dds")) {
                    throw new AssertionError("Material editor lost generated or unsaved parameters");
                }
                if (!material.asText().equals(generated)) {
                    throw new AssertionError("Reading merged editor state unexpectedly saved the file");
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        System.out.println("SKIN_MATERIAL_EDITOR_STATE_PASS");
        System.exit(0);
    }
}
