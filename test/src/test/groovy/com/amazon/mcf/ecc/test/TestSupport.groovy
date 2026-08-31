package com.amazon.mcf.ecc.test

import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Shared helpers for the offline accelerator tests.
 *
 * Loads the REAL production Groovy scripts from the iFlow folders (paths are
 * injected via system properties by build.gradle), instantiates them, and
 * provides small utilities for reading fixtures and normalizing output for
 * comparison.
 *
 * No SAP or Amazon connectivity is involved anywhere.
 */
class TestSupport {

    private static final GroovyClassLoader LOADER = new GroovyClassLoader(TestSupport.class.getClassLoader())

    /** Load and instantiate a production script by its build.gradle key (e.g. "createOrder"). */
    static Object loadScript(String key) {
        def path = System.getProperty("script.${key}")
        assert path != null : "Missing system property script.${key} (check build.gradle)"
        def file = new File(path)
        assert file.exists() : "Production script not found: ${path}"
        Class<?> clazz = LOADER.parseClass(file)
        def instance = clazz.getDeclaredConstructor().newInstance()
        // Some scripts reference the CPI binding 'messageLogFactory' at method scope.
        // Provide an offline fake so those methods run without the CPI runtime.
        try {
            instance.setProperty('messageLogFactory', newMessageLogFactory())
        } catch (ignored) {
            // Script has no such property/field — fine.
        }
        return instance
    }

    /** Offline stand-in for CPI's messageLogFactory / MessageLog. Records nothing meaningful. */
    static Object newMessageLogFactory() {
        def log = [
            addCustomHeaderProperty: { String k, String v -> },
            addAttachmentAsString  : { String a, String b, String c -> },
            setStringProperty      : { String k, String v -> }
        ] as Object
        return [ getMessageLog: { msg -> log } ] as Object
    }

    static Message newMessage(String body) {
        def m = new Message()
        m.setBody(body)
        return m
    }

    static String readSample(String name) {
        return new File(System.getProperty("samples.dir"), name).getText("UTF-8")
    }

    static String readExpected(String name) {
        return new File(System.getProperty("expected.dir"), name).getText("UTF-8")
    }

    /** Parse JSON to a canonical Map/List structure for order-insensitive comparison. */
    static Object parseJson(String json) {
        return new JsonSlurper().parseText(json)
    }

    /**
     * Normalize XML for comparison: strip whitespace between tags and trim.
     * Good enough for these RFC payloads which are deterministic apart from formatting.
     */
    static String normalizeXml(String xml) {
        return xml
            .replaceAll(/>\s+</, '><')
            .replaceAll(/\s+/, ' ')
            .trim()
    }

    /**
     * Mask runtime date/time fields so output that embeds "now" can be compared
     * against a stable expected fixture. Each masked element is replaced with a
     * fixed placeholder token; the expected fixtures use the same tokens.
     *
     * Masks:
     *   <KODAT>...</KODAT>          -> <KODAT>#DATE#</KODAT>          (picking date, yyyyMMdd)
     *   <WADAT_IST>...</WADAT_IST>  -> <WADAT_IST>#DATE#</WADAT_IST>  (goods-issue date, yyyyMMdd)
     *   <TIMESTAMP_UTC>...</TIMESTAMP_UTC> -> ...#TS#...              (delivery-change ts)
     *   the "| <timestamp> |" inside an RFC_SAVE_TEXT TDLINE -> "| #TS# |"
     */
    static String maskTimestamps(String xml) {
        return xml
            .replaceAll(/<KODAT>[^<]*<\/KODAT>/, '<KODAT>#DATE#</KODAT>')
            .replaceAll(/<WADAT_IST>[^<]*<\/WADAT_IST>/, '<WADAT_IST>#DATE#</WADAT_IST>')
            .replaceAll(/<TIMESTAMP_UTC>[^<]*<\/TIMESTAMP_UTC>/, '<TIMESTAMP_UTC>#TS#</TIMESTAMP_UTC>')
            // RFC_SAVE_TEXT milestone line: "STATUS | 2026-... | CARRIER: TRACK"
            .replaceAll(/\| \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2} \|/, '| #TS# |')
    }
}
