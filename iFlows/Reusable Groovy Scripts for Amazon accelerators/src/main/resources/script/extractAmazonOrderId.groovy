import com.sap.gateway.ip.core.customdev.util.Message
import groovy.util.XmlSlurper

Message processData(Message message) {

    // Get the incoming XML as String
    def body = message.getBody(String)
    if (!body) {
        throw new IllegalStateException("Empty message body")
    }

    // Parse XML
    def xml = new XmlSlurper().parseText(body)

    // Search for <Text> node where <LongTextID> = "0001"
    def textNode = xml.'**'.find {
        it.name() == 'Text' && it.LongTextID?.text()?.trim() == '0001'
    }

    // Extract the LongText value
    def orderId = textNode?.LongText?.text()?.trim()

    // Validate
    if (!orderId) {
        throw new IllegalStateException("No LongText found for LongTextID='0001'")
    }

    // Set in CPI property
    message.setProperty("orderID", orderId)

    return message
}
