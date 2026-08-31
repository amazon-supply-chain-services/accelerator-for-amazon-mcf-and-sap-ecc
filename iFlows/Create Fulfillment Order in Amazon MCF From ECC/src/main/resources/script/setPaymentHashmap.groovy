import com.sap.gateway.ip.core.customdev.util.Message
import javax.xml.parsers.DocumentBuilderFactory
import groovy.json.JsonOutput
import java.io.ByteArrayInputStream
import com.sap.it.api.ITApiFactory
import com.sap.it.api.mapping.ValueMappingApi

def Message processData(Message message) {

    def expectedEPType = message.getProperty("electronicPaymentType")
    def expectedAuthResult = message.getProperty("ePaytAuthorizationResult")

    def xmlInput = message.getBody(String)
    def inputStream = new ByteArrayInputStream(xmlInput.getBytes("UTF-8"))

    def dbFactory = DocumentBuilderFactory.newInstance()
    dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    dbFactory.setXIncludeAware(false)
    dbFactory.setExpandEntityReferences(false)
    dbFactory.setNamespaceAware(true)

    def dBuilder = dbFactory.newDocumentBuilder()
    def xmlDoc = dBuilder.parse(inputStream)
    xmlDoc.getDocumentElement().normalize()


def salesOrderTypeNode = xmlDoc.getElementsByTagName("A_SalesOrderType").item(0)
def paymentPlanItemsNode = salesOrderTypeNode?.getElementsByTagName("to_PaymentPlanItemDetails")?.item(0)
def nodes = paymentPlanItemsNode?.getElementsByTagName("A_SlsOrdPaymentPlanItemDetailsType")

if (!salesOrderTypeNode || !paymentPlanItemsNode || !nodes || nodes.length == 0) {
    throw new Exception("Missing or empty payment plan item data: A_SlsOrdPaymentPlanItemDetailsType")
}


    // Filter segments matching both DPVI and C
    def allowedNodes = []
    nodes.each { node ->
        def epType = node.getElementsByTagName("ElectronicPaymentType")?.item(0)?.getTextContent()?.trim()
        def authResult = node.getElementsByTagName("EPaytAuthorizationResult")?.item(0)?.getTextContent()?.trim()
        if (epType == expectedEPType && authResult == expectedAuthResult) {
            allowedNodes << node
        }
    }

    // There must be exactly one segment matching both criteria
    if (allowedNodes.size() != 1) {
        throw new Exception("Only one segment allowed with ElectronicPaymentType='${expectedEPType}' AND EPaytAuthorizationResult='${expectedAuthResult}', found ${allowedNodes.size()}")
    }

    def details = []

allowedNodes.eachWithIndex { node, idx ->
    def amountStr = node.getElementsByTagName("AuthorizedAmountInAuthznCrcy")?.item(0)?.getTextContent()
    if (!amountStr || amountStr.trim().isEmpty()) {
        throw new Exception("Missing AuthorizedAmountInAuthznCrcy in payment plan item index ${idx}")
    }
    if (!(amountStr.trim() ==~ /^-?\d*\.?\d+$/)) {
        throw new Exception("Invalid AuthorizedAmountInAuthznCrcy value '${amountStr}' in payment plan item index ${idx}")
    }
    double amount = amountStr.trim().toDouble()

    def currency = node.getElementsByTagName("AuthorizationCurrency")?.item(0)?.getTextContent()
    if (!currency || currency.trim().isEmpty()) {
        throw new Exception("Missing AuthorizationCurrency in payment plan item index ${idx}")
    }

    // Retrieve the ElectronicPaymentType from the node
    def electronicPaymentTypeValue = node.getElementsByTagName("ElectronicPaymentType")?.item(0)?.getTextContent()?.trim()
    if (!electronicPaymentTypeValue) {
        throw new Exception("Missing ElectronicPaymentType in payment plan item index ${idx}")
    }

    // Get the ValueMapping API
    def valueMapApi = ITApiFactory.getApi(ValueMappingApi.class, null)
    if (valueMapApi == null) {
        throw new Exception("Could not retrieve ValueMappingAPI.")
    }
    def paymentMethodMap = valueMapApi.getMappedValue(
       'S4HANA',                    // Source Agency
       'electronicPaymentType',     // Source Identifier  
       electronicPaymentTypeValue,  // Source Value (dynamic)
       'AMAZONBwP',                // Target Agency
       'PaymentMethod'             // Target Identifier
    )
  
    if (paymentMethodMap == null) {
        throw new Exception("No mapping found for value: " + electronicPaymentTypeValue)
    }

    def detail = [
        amount: [
            amount: amount,
            currencyCode: currency.trim()
        ],
        paymentMethod: [
            displayString: paymentMethodMap
        ]
    ]
    details << detail
}


    def jsonResult = [details: details]
    def jsonString = JsonOutput.toJson(jsonResult)
    message.setProperty("headerPaymentDetails", jsonString)

    return message
}
