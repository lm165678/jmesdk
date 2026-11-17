package jada.ngeditor.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import jada.ngeditor.model.GUI;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Shared serializer for SDK documents and the legacy editor's preview reloads.
 * currentLayer is editor selection state, not a Nifty XML element. Filtering the
 * DOM avoids mutating the live selection or emitting model notifications.
 * The legacy model declares xmlns as an ordinary JAXB attribute. Marshal to
 * XML bytes first so parsing resolves it as a namespace declaration; direct DOM
 * marshalling attempts to create an xmlns attribute without its reserved namespace.
 *
 * The legacy JAXB model does not represent every valid Nifty element. When the
 * original XML is supplied, unmodeled elements such as text, interact and effect
 * are copied back into the serialized document. Modeled elements and edited
 * attributes still come from the current GUI model.
 */
public class GUIWriter {
    private static final Set<String> MODEL_ELEMENTS = Set.of(
            "screen", "layer", "panel", "control", "image",
            "useControls", "useStyles", "currentLayer");

    private final GUI gui;
    private final String sourceXml;
    private final Marshaller marshaller;

    public GUIWriter(GUI gui) throws JAXBException, ClassNotFoundException, IOException {
        this(gui, null);
    }

    /**
     * Creates a writer that merges the current visual model with the source XML.
     * The source supplies elements and attributes unknown to the legacy model;
     * the current model remains authoritative for modeled values and hierarchy.
     *
     * @param gui current editor model
     * @param sourceXml original or last synchronized XML, or {@code null} for a
     *                  model-only serialization
     */
    public GUIWriter(GUI gui, String sourceXml)
            throws JAXBException, ClassNotFoundException, IOException {
        this.gui = gui;
        this.sourceXml = sourceXml;
        JAXBContext context = JAXBContext.newInstance(
                "jada.ngeditor.model:jada.ngeditor.model.elements:"
                + "jada.ngeditor.model.elements.specials:jada.ngeditor.model.elements.effects",
                GUIWriter.class.getClassLoader());
        marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.setProperty(Marshaller.JAXB_SCHEMA_LOCATION,
                "http://nifty-gui.lessvoid.com/nifty-gui http://nifty-gui.lessvoid.com/nifty-gui/nifty.xsd");
    }

    public InputStream getDocumentStream() throws Exception {
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        marshaller.marshal(gui, serialized);
        Document document = parse(new ByteArrayInputStream(serialized.toByteArray()));
        Node root = document.getDocumentElement();
        for (Node child = root.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if ("currentLayer".equals(child.getLocalName())) {
                root.removeChild(child);
            }
            child = next;
        }
        if (sourceXml != null) {
            preserveSourceDocument(parse(sourceXml), document);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        javax.xml.transform.Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return new ByteArrayInputStream(output.toByteArray());
    }

    public void writeGUI(String filename) throws FileNotFoundException, JAXBException {
        try (InputStream input = getDocumentStream()) {
            byte[] bytes = input.readAllBytes();
            java.nio.file.Files.write(java.nio.file.Path.of(filename), bytes);
        } catch (Exception ex) {
            throw new JAXBException("Cannot save Nifty GUI", ex);
        }
    }

    private static Document parse(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(input);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static void preserveSourceDocument(Document source, Document target) {
        Element sourceRoot = source.getDocumentElement();
        Element targetRoot = target.getDocumentElement();
        if (sourceRoot == null || targetRoot == null) {
            return;
        }
        List<Element> targetElements = new ArrayList<>();
        collectElements(targetRoot, targetElements);
        for (Element targetElement : targetElements) {
            Element sourceElement;
            if (targetElement == targetRoot) {
                sourceElement = sourceRoot;
            } else {
                sourceElement = findById(sourceRoot, targetElement.getAttribute("id"));
                if (sourceElement == null) {
                    sourceElement = findByPosition(sourceRoot, targetRoot, targetElement);
                }
            }
            if (sourceElement != null && localName(sourceElement).equals(localName(targetElement))) {
                mergeElement(sourceElement, targetElement);
            }
            removeUnsetPosition(sourceElement, targetElement);
        }
        removeUnusedDefaultReferences(sourceRoot, targetRoot);
    }

    private static void collectElements(Element element, List<Element> result) {
        result.add(element);
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                collectElements((Element) child, result);
            }
        }
    }

    private static Element findById(Element root, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (id.equals(root.getAttribute("id"))) {
            return root;
        }
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                Element found = findById((Element) child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Element findByPosition(Element sourceRoot, Element targetRoot, Element target) {
        List<Integer> positions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Node current = target;
        while (current instanceof Element && current != targetRoot) {
            Element element = (Element) current;
            Node parent = current.getParentNode();
            if (!(parent instanceof Element)) {
                return null;
            }
            String name = localName(element);
            int position = 0;
            for (Node sibling = parent.getFirstChild(); sibling != null;
                    sibling = sibling.getNextSibling()) {
                if (sibling instanceof Element
                        && name.equals(localName((Element) sibling))) {
                    if (sibling == current) {
                        break;
                    }
                    position++;
                }
            }
            names.add(name);
            positions.add(position);
            current = parent;
        }
        if (current != targetRoot) {
            return null;
        }
        Element source = sourceRoot;
        for (int i = positions.size() - 1; i >= 0; i--) {
            String name = names.get(i);
            int expected = positions.get(i);
            int position = 0;
            Element next = null;
            for (Node child = source.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element && name.equals(localName((Element) child))) {
                    if (position == expected) {
                        next = (Element) child;
                        break;
                    }
                    position++;
                }
            }
            if (next == null) {
                return null;
            }
            source = next;
        }
        return source;
    }

    private static void mergeElement(Element source, Element target) {
        copyMissingAttributes(source, target);
        preserveUnknownChildren(source, target);
    }

    private static void copyMissingAttributes(Element source, Element target) {
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName();
            if ("xmlns".equals(name) || "xmlns".equals(attribute.getPrefix())) {
                continue;
            }
            if (target.hasAttribute(name)) {
                continue;
            }
            if (attribute.getNamespaceURI() == null) {
                target.setAttribute(name, attribute.getNodeValue());
            } else {
                target.setAttributeNS(attribute.getNamespaceURI(), name, attribute.getNodeValue());
            }
        }
    }

    private static void preserveUnknownChildren(Element source, Element target) {
        for (Node child = source.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element)) {
                continue;
            }
            Element sourceChild = (Element) child;
            if (MODEL_ELEMENTS.contains(localName(sourceChild))) {
                continue;
            }
            if (hasEquivalentChild(target, sourceChild)) {
                continue;
            }
            Element before = findFollowingModelSibling(sourceChild, target);
            if (before == null) {
                before = firstNewModelChild(source, target);
            }
            Node imported = target.getOwnerDocument().importNode(sourceChild, true);
            target.insertBefore(imported, before);
        }
    }

    private static boolean hasEquivalentChild(Element target, Element sourceChild) {
        String sourceName = localName(sourceChild);
        String sourceId = sourceChild.getAttribute("id");
        for (Node child = target.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element)) {
                continue;
            }
            Element targetChild = (Element) child;
            if (!sourceName.equals(localName(targetChild))) {
                continue;
            }
            if (sourceId.isEmpty() || sourceId.equals(targetChild.getAttribute("id"))) {
                return true;
            }
        }
        return false;
    }

    private static Element findFollowingModelSibling(Element sourceChild, Element target) {
        for (Node sibling = sourceChild.getNextSibling(); sibling != null;
                sibling = sibling.getNextSibling()) {
            if (!(sibling instanceof Element)) {
                continue;
            }
            Element sourceSibling = (Element) sibling;
            if (!MODEL_ELEMENTS.contains(localName(sourceSibling))) {
                continue;
            }
            Element match = findDirectChild(target, sourceSibling);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static Element findDirectChild(Element target, Element sourceChild) {
        String id = sourceChild.getAttribute("id");
        if (id.isEmpty()) {
            return null;
        }
        for (Node child = target.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                Element targetChild = (Element) child;
                if (id.equals(targetChild.getAttribute("id"))
                        && localName(sourceChild).equals(localName(targetChild))) {
                    return targetChild;
                }
            }
        }
        return null;
    }

    private static Element firstNewModelChild(Element source, Element target) {
        Element sourceRoot = source.getOwnerDocument().getDocumentElement();
        for (Node child = target.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element)) {
                continue;
            }
            Element targetChild = (Element) child;
            if (!MODEL_ELEMENTS.contains(localName(targetChild))) {
                continue;
            }
            String id = targetChild.getAttribute("id");
            if (!id.isEmpty() && findById(sourceRoot, id) == null) {
                return targetChild;
            }
        }
        return null;
    }

    private static void removeUnsetPosition(Element source, Element target) {
        String[] names = {"x", "y"};
        for (String name : names) {
            if ("-1px".equals(target.getAttribute(name))
                    && (source == null || !source.hasAttribute(name))) {
                target.removeAttribute(name);
            }
        }
    }

    private static void removeUnusedDefaultReferences(Element sourceRoot, Element targetRoot) {
        if (targetRoot.getElementsByTagNameNS("*", "control").getLength() > 0) {
            return;
        }
        removeDefaultReference(sourceRoot, targetRoot, "useStyles", "nifty-default-styles.xml");
        removeDefaultReference(sourceRoot, targetRoot, "useControls", "nifty-default-controls.xml");
    }

    private static void removeDefaultReference(Element sourceRoot, Element targetRoot,
            String tag, String filename) {
        if (hasReference(sourceRoot, tag, filename)) {
            return;
        }
        for (Node child = targetRoot.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if (child instanceof Element) {
                Element element = (Element) child;
                if (tag.equals(localName(element)) && filename.equals(element.getAttribute("filename"))) {
                    targetRoot.removeChild(element);
                }
            }
            child = next;
        }
    }

    private static boolean hasReference(Element root, String tag, String filename) {
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                Element element = (Element) child;
                if (tag.equals(localName(element)) && filename.equals(element.getAttribute("filename"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
    }
}
