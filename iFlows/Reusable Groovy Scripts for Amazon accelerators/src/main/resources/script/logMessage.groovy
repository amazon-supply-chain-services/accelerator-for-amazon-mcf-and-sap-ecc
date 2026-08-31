import com.sap.gateway.ip.core.customdev.util.Message;

def Message processData(Message message) 
{
	def properties = message.getProperties();
	if(properties.get("enableLogging") != null && properties.get("enableLogging").toString().equalsIgnoreCase("false"))
	{
		return message;
	}
	def body = message.getBody(java.lang.String) as String;
	def messageLog = messageLogFactory.getMessageLog(message);
	if(messageLog != null)
	{
	messageLog.addAttachmentAsString(properties.get("title"), body, "text/plain");
     }
	return message;
}
