//logs the payload as attachment

import com.sap.gateway.ip.core.customdev.util.Message
import java.io.PrintWriter
import java.io.StringWriter

def Message processData(Message message) {

        // In Exception subprocess, the caught exception is stored as property "CamelExceptionCaught"
        def ex = message.getProperty("CamelExceptionCaught")
        def log = messageLogFactory.getMessageLog(message)

        if (ex != null) {
            def errorMsg = ex.getMessage()
            def cause = ex.getCause()?.toString()

            def sw = new StringWriter()
            ex.printStackTrace(new PrintWriter(sw))
            def stacktrace = sw.toString()

            // Store in properties for later usage
            message.setProperty("ExceptionMessage", errorMsg)
            message.setProperty("ExceptionCause", cause)
            message.setProperty("ExceptionStacktrace", stacktrace)

            // Log to MPL as attachments
            if (log != null) {
                log.addAttachmentAsString("Exception Message", errorMsg, "text/plain")
                log.addAttachmentAsString("Exception Cause", cause ?: "N/A", "text/plain")
                log.addAttachmentAsString("Stacktrace", stacktrace, "text/plain")
            }
        } else {
            if (log != null) {
                log.addAttachmentAsString("Exception Logging", "No exception found in message context", "text/plain")
            }
        }
    return message
}
