package groovy.util

/**
 * Compatibility shim for running the production iFlow scripts under modern Groovy (4.x) in tests.
 *
 * SAP CPI runs Groovy 2.4, where XmlSlurper lives in the `groovy.util` package.
 * The production scripts therefore `import groovy.util.XmlSlurper`. In Groovy 3+
 * the class moved to `groovy.xml.XmlSlurper` and the old package location was
 * removed, so those imports fail to compile under Groovy 4.
 *
 * This subclass restores `groovy.util.XmlSlurper` (delegating to the real
 * `groovy.xml.XmlSlurper`) so the UNMODIFIED production scripts compile and run
 * in the offline test harness. It changes nothing about the production code or
 * its behavior on the actual CPI runtime.
 *
 * Only used in the test source set.
 */
class XmlSlurper extends groovy.xml.XmlSlurper {
    XmlSlurper() { super() }
    XmlSlurper(boolean validating, boolean namespaceAware) { super(validating, namespaceAware) }
    XmlSlurper(boolean validating, boolean namespaceAware, boolean allowDocTypeDeclaration) {
        super(validating, namespaceAware, allowDocTypeDeclaration)
    }
}
