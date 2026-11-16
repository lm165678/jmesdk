package jada.ngeditor.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
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
import org.w3c.dom.Node;

/**
 * Shared serializer for SDK documents and the legacy editor's preview reloads.
 * currentLayer is editor selection state, not a Nifty XML element. Filtering the
 * DOM avoids mutating the live selection or emitting model notifications.
 * The legacy model declares xmlns as an ordinary JAXB attribute. Marshal to
 * XML bytes first so parsing resolves it as a namespace declaration; direct DOM
 * marshalling attempts to create an xmlns attribute without its reserved namespace.
 */
public class GUIWriter {
    private final GUI gui;
    private final Marshaller marshaller;

    public GUIWriter(GUI gui) throws JAXBException, ClassNotFoundException, IOException {
        this.gui = gui;
        JAXBContext context = JAXBContext.newInstance(
                "jada.ngeditor.model:jada.ngeditor.model.elements:jada.ngeditor.model.elements.specials",
                GUIWriter.class.getClassLoader());
        marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.setProperty(Marshaller.JAXB_SCHEMA_LOCATION,
                "http://nifty-gui.lessvoid.com/nifty-gui http://nifty-gui.lessvoid.com/nifty-gui/nifty.xsd");
    }

    public InputStream getDocumentStream() throws Exception {
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        marshaller.marshal(gui, serialized);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(serialized.toByteArray()));
        Node root = document.getDocumentElement();
        for (Node child = root.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if ("currentLayer".equals(child.getLocalName())) {
                root.removeChild(child);
            }
            child = next;
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
}
