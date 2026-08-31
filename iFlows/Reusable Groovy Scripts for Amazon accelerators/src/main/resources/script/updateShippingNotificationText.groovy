import com.sap.gateway.ip.core.customdev.util.Message
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

def Message processData(Message message) {

    // --- Step 1: Validate mandatory properties ---
    def language = message.getProperty("language")
    def textElement = message.getProperty("textElement")
    def deliveryDocument = message.getProperty("deliveryDocument")
    
    
    //estimatedDeliveryDate latest date 
        def deliveryDate = message.getProperty("deliveryDate").take(19)
        def currentDate = message.getProperty("currentDate").take(19)
        
        def inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    def milestoneLocalDateTime = LocalDateTime.parse(deliveryDate, inputFormatter)
    def milestoneGIDateStr = milestoneLocalDateTime.toLocalDate().toString() + "T00:00:00"
    def milestoneGITimeStr = deliveryDate.split("T")[1]

    def deliveredLocalDateTime = LocalDateTime.parse(currentDate, inputFormatter)
    def deliveredGIDateStr = deliveredLocalDateTime.toLocalDate().toString() + "T00:00:00"
    def deliveredGITimeStr = currentDate.split("T")[1]

    // Set as message properties
    message.setProperty("milestoneGIDate", milestoneGIDateStr)
    message.setProperty("milestoneGITime", milestoneGITimeStr)
    message.setProperty("deliveredDate", deliveredGIDateStr)
    message.setProperty("deliveredTime", deliveredGITimeStr)
        

    def missingFields = []

    if (!language?.trim()) missingFields << "language"
    if (!textElement?.trim()) missingFields << "textElement"
    if (!deliveryDocument?.trim()) missingFields << "deliveryDocument"
    if (!deliveryDate?.trim()) missingFields << "deliveryDate"

    if (!missingFields.isEmpty()) {
        def errorMessage = "Missing or empty mandatory properties: ${missingFields.join(', ')}"
        throw new Exception(errorMessage)
    }

    // --- Step 2: Build modified text element text ---
    def TextElementText = message.getProperty("textElementText")
    def currentDateText = message.getProperty("currentDate")
    def state = message.getProperty("state")
    def reason = message.getProperty("reason")
    def trackingID = message.getProperty("trackingID")
    def carrierCode = message.getProperty("carrierCode")
    def milestoneStatusCode = message.getProperty("milestoneStatusCode")

    def modifiedTextElementText = TextElementText +
        "\n---------------------\n" +
        "\nDate: ${currentDateText}\n" +
        "State: ${state}\n" +
        "Reason: ${reason}\n" +
        "Tracking ID: ${trackingID}\n" +
        "Carrier Code: ${carrierCode}\n" +
        "MilestoneStatusCode: ${milestoneStatusCode}\n"

    // --- Step 3: Set the new property ---
    message.setProperty("modifiedTextElementText", modifiedTextElementText)

    return message
}
