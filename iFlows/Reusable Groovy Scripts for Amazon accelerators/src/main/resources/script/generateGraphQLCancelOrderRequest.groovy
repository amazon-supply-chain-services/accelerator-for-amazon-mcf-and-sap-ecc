import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import com.sap.it.api.ITApiFactory
import com.sap.it.api.securestore.SecureStoreService
import com.sap.it.api.mapping.ValueMappingApi

Message processData(Message message) {

    def props = message.getProperties()

    // Externalized configurable values
    def configuredLongTextId = props.get("longTextId")
    def configuredPlant = props.get("plant")
    def sellerID = props.get("SellerID")

    // Get SOAP body
    def body = message.getBody(String)
    if (!body) {
        throw new IllegalStateException("Empty message body")
    }
    def xml = new XmlSlurper().parseText(body)

    // Extract key fields
    def overallReject = xml.'**'.find { it.name() == 'OverallSDDocumentRejectionSts' }?.text()?.trim()
    def items = xml.'**'.findAll { it.name() == 'Item' }
    if (!items) throw new IllegalStateException("No <Item> elements found in SalesOrder XML")

    // Filter items for configured plant
    def relevantItems = items.findAll { it.Plant?.text()?.trim() == configuredPlant }
    def allItemRejectC = relevantItems && relevantItems.every { it.SDDocumentRejectionStatus?.text()?.trim() == 'C' }

    boolean cancelOrderRequired = (overallReject == 'C') || allItemRejectC

    // 🆕 Capture the first valid SalesDocumentRjcnReason
    def rejReason = null
    if (overallReject == 'C') {
        // if overall rejection is C, search for first non-empty SalesDocumentRjcnReason in items with plant configred
        rejReason = relevantItems.find {
            def reason = it.SalesDocumentRjcnReason?.text()?.trim()
            return reason && !reason.isEmpty()
        }?.SalesDocumentRjcnReason?.text()?.trim()
    } else {
        // if not overall reject C, check for items with plant configured and non-empty reason
        rejReason = relevantItems.find {
            def reason = it.SalesDocumentRjcnReason?.text()?.trim()
            return reason && !reason.isEmpty()
        }?.SalesDocumentRjcnReason?.text()?.trim()
    }

    // Store reason into message property if found
    if (rejReason) {
        message.setProperty("rejReason", rejReason)
    }
    
    // Value Mapping Lookup to find reason text
if (rejReason) {
    try {
        def valueMappingApi = ITApiFactory.getApi(ValueMappingApi.class, null)
        def mappedText = valueMappingApi.getMappedValue(
            "SAP",                  // Source Agency
            "RejReasonSAP",         //Source Identifier
            rejReason,             // input
            "AMAZON",               // Target Agency
            "RejReasonAmazon"       // Target Identifier
        )

        if (mappedText) {
            message.setProperty("rejReasonText", mappedText)
        } else { 
            throw new Exception("Mapping not found for reason: ${rejReason}")
        }

    } catch (Exception e) {
        // throw exception
        throw new Exception("Mapping lookup failed: ${e.message}", e)
    }
}

    

    if (!cancelOrderRequired) {
        throw new IllegalStateException("This Sales Order does not qualify for cancellation.")
    }

    // Extract LongText and IDs
    def longTextNode = xml.'**'.find { it.name() == 'Text' && it.LongTextID.text()?.trim() == configuredLongTextId }
    def orderId = longTextNode?.LongText?.text()?.trim()
    def longTextIdFromXml = longTextNode?.LongTextID?.text()?.trim()
    def externalDocId = xml.'**'.find { it.name() == 'ExternalDocumentID' }?.text()?.trim() ?: props.get("aliasId")

    if (!orderId && !externalDocId)
        throw new IllegalStateException("Neither LongText nor ExternalDocumentID present")
    
    def rejReasonText = message.getProperty("rejReasonText") ?: "Cancellation reason missing" // TO DO: Change to Default value
    
    // Build GraphQL query
    def graphQLQuery = ""
    if (orderId && longTextIdFromXml == configuredLongTextId) {
        graphQLQuery = """mutation cancelOrder {
  cancelOrder(
    orderIdentifier: { orderId: "${orderId}" }
    input: { reason: ${rejReasonText} }
  ) {
    cancellation {
      id
    }
  }
}"""
    } else if (externalDocId) {
        graphQLQuery = """mutation cancelOrder {
  cancelOrder(
    orderIdentifier: { alias: { aliasId: "${externalDocId}", aliasType: "${sellerID}" } }
    input: { reason: ${rejReasonText} }
  ) {
    cancellation {
      id
    }
  }
}"""
    } else {
        throw new IllegalStateException("LongTextID '${longTextIdFromXml}' does not match '${configuredLongTextId}' and no alias found.")
    }

    // Wrap as JSON
    def graphqlJson = JsonOutput.toJson([query: graphQLQuery])
    message.setBody(graphqlJson)
    message.setHeader("Content-Type", "application/json")

    // Secure Store headers
    String apiTargetIDSecureParameterName = props.get("apiTargetIdSecureParameter")
    String apiAccessKeySecureParameterName = props.get("apiAccessKeySecureParameter")

    def secureStoreService = ITApiFactory.getApi(SecureStoreService.class, null)
    def apiTargetIDSecureParameter = secureStoreService.getUserCredential(apiTargetIDSecureParameterName)
    def apiAccessKeySecureParameter = secureStoreService.getUserCredential(apiAccessKeySecureParameterName)

    if (apiTargetIDSecureParameter == null || apiTargetIDSecureParameter.getPassword() == null) {
        throw new IllegalStateException("Missing secure parameter '${apiTargetIDSecureParameterName}' in Secure Store")
    }
    if (apiAccessKeySecureParameter == null || apiAccessKeySecureParameter.getPassword() == null) {
        throw new IllegalStateException("Missing secure parameter '${apiAccessKeySecureParameterName}' in Secure Store")
    }

    String apiTargetID = new String(apiTargetIDSecureParameter.getPassword())
    String apiAccessKey = new String(apiAccessKeySecureParameter.getPassword())

    message.setHeader("x-api-access-key", apiAccessKey)
    message.setHeader("X-Omni-TargetId", apiTargetID)
    message.setHeader("x-api-version", props.get("apiVersion"))

    // CPI Debug Attachments
    def messageLog = messageLogFactory?.getMessageLog(message)
    if (messageLog != null) {
        messageLog.addAttachmentAsString("After Mapping Message", graphQLQuery, "text/plain")
    }

    return message
}
