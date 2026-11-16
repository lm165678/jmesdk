package com.jme3.gde.core.assets;

import com.jme3.asset.TextureKey;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.gde.texturetools.GeneratedTextures;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.texture.Texture;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.openide.filesystems.FileUtil;

/** CPU-only integration: conversion preparation, mapped J3M and embedded J3O materials. */
public final class ModelTextureMappingSmoke {
    public static void main(String[] args) {
        try {
            run(Path.of(args[0]));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void run(Path work) throws Exception {
        Path root = Files.createTempDirectory(work, "model-mapping-").toAbsolutePath();
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        ImageIO.write(image, "png", root.resolve("color.png").toFile());
        FileUtil.refreshFor(root.toFile());
        ProjectAssetManager manager = new ProjectAssetManager(FileUtil.toFileObject(root.toFile()));
        TextureKey key = new TextureKey("color.png", true);
        key.setGenerateMips(true);
        key.setAnisotropy(4);
        Texture original = manager.loadTexture(key);
        require(!original.getImage().getFormat().isCompressed(), "Source should initially load PNG");
        Material material = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setTexture("ColorMap", original);
        Geometry geometry = new Geometry("model", new Box(1, 1, 1));
        geometry.setMaterial(material);
        Node model = new Node("root");
        model.attachChild(geometry);
        ModelTexturePreparation.prepare(model, manager);
        GeneratedTextures store = new GeneratedTextures(root);
        Path output = store.resolve("color.png");
        require(output != null, "Conversion did not generate texture");
        Texture mapped = manager.loadTexture(key);
        require(mapped.getImage().getFormat().isCompressed(), "DDS variant was not loaded");
        require(mapped.getKey().equals(key), "Logical key or texture flags changed");
        require(mapped.getAnisotropicFilter() == 4, "Anisotropy lost");
        BinaryExporter.getInstance().save(model, root.resolve("model.j3o").toFile());
        FileUtil.refreshFor(root.toFile());
        Node restored = (Node) manager.loadModel("model.j3o");
        Texture embedded = ((Geometry) restored.getChild(0)).getMaterial().getTextureParam("ColorMap").getTextureValue();
        require(embedded.getImage().getFormat().isCompressed(), "Embedded J3O material bypassed mapping");
        require(embedded.getKey().getName().equals("color.png"), "J3O persisted a physical DDS key");
        Files.writeString(root.resolve("external.j3m"), "Material test : Common/MatDefs/Misc/Unshaded.j3md {\n"
                + "MaterialParameters {\nColorMap : color.png\n}\n}\n");
        Texture external = manager.loadMaterial("external.j3m").getTextureParam("ColorMap").getTextureValue();
        require(external.getImage().getFormat().isCompressed(), "External J3M bypassed mapping");
        manager.setUseGeneratedTextures(false);
        require(!manager.loadTexture(key).getImage().getFormat().isCompressed(), "Source preview switch failed");
        manager.setUseGeneratedTextures(true);
        require(manager.loadTexture(key).getImage().getFormat().isCompressed(), "DDS restore switch failed");
        Files.delete(output);
        require(!manager.loadTexture(key).getImage().getFormat().isCompressed(), "Missing DDS fallback failed");
        System.out.println("PASS conversion, logical key flags, external J3M, embedded J3O, switching and missing-output fallback");
    }
}
