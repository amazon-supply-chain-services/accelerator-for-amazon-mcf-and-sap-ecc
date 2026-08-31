package com.sap.gateway.ip.core.customdev.util

/**
 * Minimal offline stand-in for the SAP CPI `Message` class
 * (com.sap.gateway.ip.core.customdev.util.Message).
 *
 * The production Groovy scripts in the iFlows import this exact class.
 * The real class is only available inside the SAP BTP Cloud Integration
 * runtime and is NOT redistributable. This stub implements just the
 * methods the accelerator scripts use, so those scripts can be executed
 * unchanged in a local JUnit test — with no SAP system, no Amazon
 * connectivity, and no network.
 *
 * Only the subset of the Message API used by the accelerator is provided:
 *   getBody(), getBody(Class), setBody(Object),
 *   getProperty(String), setProperty(String, Object), getProperties(),
 *   getHeader(String, Class), setHeader(String, Object), getHeaders()
 */
class Message {

    private Object body
    private final Map<String, Object> properties = new HashMap<>()
    private final Map<String, Object> headers = new HashMap<>()

    // --- Body ---
    Object getBody() {
        return body
    }

    /** CPI supports getBody(Class) for type coercion; we return the body as-is
     *  (scripts request String.class / java.lang.String). */
    def <T> T getBody(Class<T> type) {
        if (body == null) return null
        if (type == String.class && !(body instanceof String)) {
            return (T) body.toString()
        }
        return (T) body
    }

    void setBody(Object newBody) {
        this.body = newBody
    }

    // --- Properties ---
    Object getProperty(String name) {
        return properties.get(name)
    }

    void setProperty(String name, Object value) {
        properties.put(name, value)
    }

    Map<String, Object> getProperties() {
        return properties
    }

    // --- Headers ---
    Object getHeader(String name) {
        return headers.get(name)
    }

    def <T> T getHeader(String name, Class<T> type) {
        return (T) headers.get(name)
    }

    void setHeader(String name, Object value) {
        headers.put(name, value)
    }

    Map<String, Object> getHeaders() {
        return headers
    }
}
