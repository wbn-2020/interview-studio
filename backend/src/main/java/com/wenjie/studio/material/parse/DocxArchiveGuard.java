package com.wenjie.studio.material.parse;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class DocxArchiveGuard {
    private static final int MAX_ENTRIES = 2000;
    private static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final String DOCUMENT_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final Set<String> ACTIVE_RELATIONSHIP_NAMES = Set.of(
            "vbaproject", "wordvbadata", "vbaprojectsignature", "vbaprojectsignatureagile", "vbaprojectsignaturev3",
            "oleobject", "package", "control", "activexcontrol", "activexcontrolbinary", "macrocache");
    private static final Set<String> ACTIVE_CONTENT_TYPES = Set.of(
            "application/vnd.ms-office.vbaproject",
            "application/vnd.ms-office.vbaprojectsignature",
            "application/vnd.ms-office.vbaprojectsignatureagile",
            "application/vnd.ms-office.vbaprojectsignaturev3",
            "application/vnd.ms-word.vbadata+xml",
            "application/vnd.ms-word.macrocache+xml",
            "application/vnd.openxmlformats-officedocument.oleobject",
            "application/vnd.openxmlformats-officedocument.package",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.template",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
            "application/vnd.ms-office.activex",
            "application/vnd.ms-office.activex+xml");

    private DocxArchiveGuard() {}

    static void inspect(Path input) throws IOException, XMLStreamException, ParserMain.Rejected {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("EXTERNAL_XML_FORBIDDEN");
        });
        Set<String> names = new HashSet<>();
        long total = 0;
        int entries = 0;
        boolean mainPart = false;
        boolean contentTypes = false;
        try (ZipFile zip = new ZipFile(input.toFile())) {
            var enumeration = zip.entries();
            byte[] buffer = new byte[8192];
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (++entries > MAX_ENTRIES || !names.add(name)) throw new ParserMain.Rejected("DOCX_ARCHIVE_LIMIT");
                if (name.isBlank() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                        || name.indexOf('\0') >= 0 || java.util.Arrays.stream(name.split("/")).anyMatch(".."::equals)) {
                    throw new ParserMain.Rejected("DOCX_ENTRY_INVALID");
                }
                if (lower.contains("vbaproject") || lower.startsWith("word/embeddings/")) {
                    throw new ParserMain.Rejected("DOCX_ACTIVE_CONTENT_UNSUPPORTED");
                }
                if (entry.isDirectory()) continue;
                if (entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES || entry.getCompressedSize() < 0) {
                    throw new ParserMain.Rejected("DOCX_ARCHIVE_LIMIT");
                }
                long actual = 0;
                try (InputStream stream = zip.getInputStream(entry)) {
                    int count;
                    while ((count = stream.read(buffer)) >= 0) {
                        actual += count;
                        total += count;
                        if (actual > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES
                                || actual > Math.max(1L, entry.getCompressedSize()) * 100L) {
                            throw new ParserMain.Rejected("DOCX_ARCHIVE_LIMIT");
                        }
                    }
                }
                if (actual != entry.getSize()) throw new ParserMain.Rejected("DOCX_ARCHIVE_INVALID");
                if (name.equals("word/document.xml")) mainPart = true;
                if (lower.endsWith(".xml") || lower.endsWith(".rels")) {
                    try (InputStream stream = zip.getInputStream(entry)) {
                        XMLStreamReader reader = factory.createXMLStreamReader(stream);
                        try {
                            while (reader.hasNext()) {
                                int event = reader.next();
                                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                                    throw new ParserMain.Rejected("DOCX_XML_UNSUPPORTED");
                                }
                                if (event != XMLStreamConstants.START_ELEMENT) continue;
                                String element = reader.getLocalName();
                                if (lower.endsWith(".rels") && element.equals("Relationship")) {
                                    // Part 可改名或移动，活动内容还必须按关系语义与内容声明识别。
                                    if (activeRelationship(reader.getAttributeValue(null, "Type"))) {
                                        throw new ParserMain.Rejected("DOCX_ACTIVE_CONTENT_UNSUPPORTED");
                                    }
                                    String mode = reader.getAttributeValue(null, "TargetMode");
                                    String target = reader.getAttributeValue(null, "Target");
                                    if ("External".equalsIgnoreCase(mode) || target != null
                                            && (target.contains(":") || target.startsWith("//"))) {
                                        throw new ParserMain.Rejected("DOCX_EXTERNAL_RELATIONSHIP");
                                    }
                                }
                                if (name.equals("[Content_Types].xml")
                                        && (element.equals("Default") || element.equals("Override"))) {
                                    String contentType = reader.getAttributeValue(null, "ContentType");
                                    if (activeContentType(contentType)) {
                                        throw new ParserMain.Rejected("DOCX_ACTIVE_CONTENT_UNSUPPORTED");
                                    }
                                    if (element.equals("Override") && "/word/document.xml".equals(reader.getAttributeValue(null, "PartName"))) {
                                        if (!DOCUMENT_CONTENT_TYPE.equals(contentType)) throw new ParserMain.Rejected("DOCX_FORMAT_UNSUPPORTED");
                                        contentTypes = true;
                                    }
                                }
                            }
                        } finally { reader.close(); }
                    }
                }
            }
        }
        if (!mainPart || !contentTypes) throw new ParserMain.Rejected("DOCX_FORMAT_UNSUPPORTED");
    }

    private static boolean activeRelationship(String type) {
        if (type == null) return false;
        String normalized = type.strip().toLowerCase(Locale.ROOT);
        boolean officeRelationship = normalized.startsWith("http://schemas.openxmlformats.org/officedocument/2006/relationships/")
                || normalized.startsWith("http://purl.oclc.org/ooxml/officedocument/relationships/")
                || normalized.startsWith("http://schemas.microsoft.com/office/") && normalized.contains("/relationships/");
        return officeRelationship && ACTIVE_RELATIONSHIP_NAMES.contains(normalized.substring(normalized.lastIndexOf('/') + 1));
    }

    private static boolean activeContentType(String type) {
        if (type == null) return false;
        int parameter = type.indexOf(';');
        String mediaType = (parameter < 0 ? type : type.substring(0, parameter)).strip().toLowerCase(Locale.ROOT);
        return mediaType.contains("macroenabled") || ACTIVE_CONTENT_TYPES.contains(mediaType);
    }
}
