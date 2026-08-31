import com.sap.gateway.ip.core.customdev.util.Message;
import groovy.json.JsonBuilder


def Message processData(Message message) {
    
    def messageLog = messageLogFactory.getMessageLog(message)

    // Fetch dynamic properties from CPI
    def orderId = message.getProperty("amazonOrderId")
    def refundDetailId = message.getProperty("refundId")
    def aliasTypeExpected = message.getProperty("aliasTypeforUpdate")
    def aliasIdExpected = message.getProperty("cmID")
    def refundAmount = message.getProperty("refundTotalalAmount")
    def currencyCode = message.getProperty("refundTotalCurrenyCode")

    // Build GraphQL mutation
    def mutationQuery = """mutation updateOrder {
        updateOrder(
            orderIdentifier: { orderId: "$orderId" }
            input: {
                refunds: {
                    details: [
                        {
                            id: "$refundDetailId"
                            aliases: [
                                { aliasType: "$aliasTypeExpected", aliasId: "$aliasIdExpected" }
                            ]
                            refundTotal: { totalAmount: { amount: $refundAmount, currencyCode: "$currencyCode" } }
                        }
                    ]
                }
            }
        ) {
            order {
                id
                refunds {
                    details {
                        id
                        state
                        aliases { aliasType aliasId }
                    }
                }
            }
        }
    }"""
    
    //Set custom header
    messageLog.addCustomHeaderProperty("Credit Memo Number", aliasIdExpected)

    // Convert the mutation into a JSON payload
    def jsonBody = new JsonBuilder([query: mutationQuery]).toString()
    message.setBody(jsonBody)

    return message
}