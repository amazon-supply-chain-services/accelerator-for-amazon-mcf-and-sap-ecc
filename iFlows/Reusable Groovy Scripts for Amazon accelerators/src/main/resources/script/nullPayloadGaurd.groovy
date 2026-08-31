//To Prevent NoTypeConversionAvailableException when payload is null
import com.sap.gateway.ip.core.customdev.util.Message

def Message processData(Message message) {
    def body = message.getBody(String)
    if (!body) {
        body = "{}" // or "<empty/>"
        message.setBody(body)
    }
    return message
}