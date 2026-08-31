import com.sap.gateway.ip.core.customdev.util.Message
import groovy.util.XmlSlurper

/**
 * Validates delivery has data and item plant matches configured plant.
 * Sets createOrder = 'yes' if valid, 'no' otherwise.
 */
def Message verifyLongTextID(Message message) {
    def body = message.getBody(String)
    def xml = new XmlSlurper(false, false).parseText(body)

    def configuredPlant = message.getProperty("amazonPlant") ?: ""

    // Find ET_DELIVERY_HEADER anywhere in the document
    def headerNode = xml.depthFirst().find { it.name() == 'ET_DELIVERY_HEADER' }

    if (!headerNode || headerNode.item.size() == 0) {
        message.setProperty("createOrder", "no")
        return message
    }

    // Find ET_DELIVERY_ITEM anywhere in the document
    def itemNode = xml.depthFirst().find { it.name() == 'ET_DELIVERY_ITEM' }

    def hasMatchingPlant = itemNode?.item?.any { item ->
        item.WERKS.text()?.trim() == configuredPlant
    } ?: false

    message.setProperty("createOrder", hasMatchingPlant ? "yes" : "no")
    return message
}

/**
 * Set Custom Header for precheck Failure
 */
def Message setCustomHeader(Message message) {
    def messageLog = messageLogFactory.getMessageLog(message)
    messageLog.addCustomHeaderProperty("Amazon PreCheck Failure", "Missing/Invalid delivery data or plant mismatch for Amazon processing")
    return message
}
