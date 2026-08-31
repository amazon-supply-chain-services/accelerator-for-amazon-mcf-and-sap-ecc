import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper

Message processData(Message message) {

    def body = message.getBody(String)
    def json = new JsonSlurper().parseText(body)

    def sellerIDFound = "N"
    def extIDFound = "N"
    def sellerID = null
    def externalID = null

    // Extract aliases array
    def aliases = json?.data?.order?.aliases

    if (aliases) {
        aliases.each { alias ->
            if (alias.aliasType == "SELLER_ID") {
                sellerID = alias.aliasId
                sellerIDFound = "Y"
            } else if (alias.aliasType == "EXTERNAL_ID") {
                externalID = alias.aliasId
                extIDFound = "Y"
            }
        }
    }

    

    // Set values to message properties
    message.setProperty("sellerID", sellerID)
    message.setProperty("externalID", externalID)
    message.setProperty("sellerIDFound", sellerIDFound)
    message.setProperty("extIDFound", extIDFound)

    return message
}
