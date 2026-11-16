import java.nio.file.Path;
import java.util.jar.JarFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.openide.modules.Dependency;

/** Checks source declarations and the packaged manifest with NetBeans' own parser. */
public final class ModuleDependencySmoke {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var factory = DocumentBuilderFactory.newInstance();
        var document = factory.newDocumentBuilder().parse(
                root.resolve("jme3-core/nbproject/project.xml").toFile());
        var dependencies = document.getElementsByTagName("dependency");
        var names = new java.util.HashSet<String>();
        for (int i = 0; i < dependencies.getLength(); i++) {
            var element = (org.w3c.dom.Element) dependencies.item(i);
            String name = element.getElementsByTagName("code-name-base").item(0).getTextContent();
            if (!names.add(name)) {
                throw new AssertionError("Duplicate source dependency: " + name);
            }
        }
        try (JarFile jar = new JarFile(root.resolve("build/cluster/modules/com-jme3-gde-core.jar").toFile())) {
            String value = jar.getManifest().getMainAttributes().getValue("OpenIDE-Module-Module-Dependencies");
            var parsed = Dependency.create(Dependency.TYPE_MODULE, value);
            long jna = parsed.stream().filter(d -> d.getName().equals("org.netbeans.libs.jna/2")).count();
            if (jna != 1) {
                throw new AssertionError("Expected exactly one JNA dependency, got " + jna);
            }
        }
        System.out.println("SDK_CORE_DEPENDENCY_MANIFEST_PASS");
    }
}
