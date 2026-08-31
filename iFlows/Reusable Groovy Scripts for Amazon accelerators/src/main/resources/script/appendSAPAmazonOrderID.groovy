import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.XmlUtil
import groovy.util.XmlParser

Message processData(Message message) {

       // Step 1: Read incoming and original payloads
   
    def incomingXml = message.getBody(String)?.trim()
    def originalXml = message.getProperty("originalPayload")?.trim()

    if (!incomingXml || !originalXml) {
        message.setProperty("AppendStatus", "Missing body or OriginalPayload")
        return message
    }
   
    // Step 2: Parse XMLs
   
    def parser = new XmlParser(false, false)
    def incoming = parser.parseText(incomingXml)
    def original = parser.parseText(originalXml)
   
    // Step 3: Build Material → LineItemId map from incoming payload
   
    // Example: [TG40: '5cd634ed8a-d98e0da08a', TG50: '4ce33cac66-4cd1ccacaf']
    def materialMap = incoming.order.lineItems.collectEntries { item ->
        def mat = item.product?.externalId?.value?.text()?.trim()
        def id = item.id?.text()?.trim()
        return (mat && id) ? [(mat): id] : [:]        
    }

      // Step 4: Insert AMAZON_EXTERNAL_ID into OriginalPayload
   
    // Traverse all <Item> nodes and insert AMAZON_EXTERNAL_ID after <Material>
    original.depthFirst()
        .findAll { it.name() == 'Item' }
        .each { item ->

            def matVal = item.Material?.text()?.trim()
            def lineItemId = materialMap[matVal]

            if (!lineItemId) return // skip if no match

            // 4.1 Remove any existing AMAZON_EXTERNAL_ID nodes
            item.children().removeAll { c ->
                (c instanceof Node) && (c.name()?.toString() == 'AMAZON_EXTERNAL_ID')
            }

            // 4.2 Find <Material> index position
            def children = item.children()
            def matIndex = children.findIndexOf { c ->
                (c instanceof Node) && (c.name()?.toString() == 'Material')
            }

            // 4.3 Create and insert AMAZON_EXTERNAL_ID node exactly once
            def amazonNode = new Node(null, "AMAZON_EXTERNAL_ID", lineItemId)
           if (matIndex >= 0)
                children.add(matIndex + 1, amazonNode)
            else
                item.append(amazonNode)
        }

   
    // Step 5: Serialize output and set CPI properties
   
    def result = XmlUtil.serialize(original)
    message.setBody(result)

    message.setProperty("AppendStatus", "Success. Materials processed: ${materialMap}")
    
    return message
}
 