import com.sap.gateway.ip.core.customdev.util.Message
import groovy.util.XmlSlurper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def processData(Message message) {
    def root = new XmlSlurper(false, false).parseText(message.getBody(java.lang.String) as String)
 
   // Construct JSON body for GraphQL request
        def jsonMap = [:]

        def alias = populateHeaderAliases(root, message) 
        if (alias != null) jsonMap.aliases = alias

        def customer = populateCustomer(root, message)
        if (customer != null) jsonMap.customer = customer

        def desiredState = populateDesiredExecutionState(root, message)
        if (desiredState != null) jsonMap.desiredExecutionState = desiredState

        def lineItems = populateLineItems(root, message)
        if (lineItems != null) jsonMap.lineItems = lineItems

        def recipient = populateHeaderRecipient(root, message)
        if (recipient != null) jsonMap.recipient = recipient

        def taxes = populateHeaderTaxSummary(root, message)
        if (taxes != null) jsonMap.taxes = taxes

        def totalPrice = populateHeaderTotalPrice(root)
        if (totalPrice != null) jsonMap.totalPrice = totalPrice

        def discounts = populateHeaderDiscounts(root, message)
        if (discounts != null) jsonMap.discounts = discounts

        def payments = populatePayments(message)
        if (payments != null) jsonMap.payments = payments
        
        def shopperIdentity = populateShopperIdentity(message)
        if (shopperIdentity != null) jsonMap.shopperIdentity = shopperIdentity

    // GraphQL mutation and variables
    def mutation = '''mutation createOrder($input: CreateOrderInput!) { createOrder(input: $input) { order { id lineItems { aliases { aliasId } id } orderLinks { destinationType url } } } }'''
    def variables = [input: jsonMap]
    def graphqlRequest = [
        query: mutation,
        variables: variables
    ]
    def jsonOutput = JsonOutput.toJson(graphqlRequest)
    
    message.setBody(jsonOutput)
    return message
}

//-------------------------------------------------------------------------------------------------------//
// Header Functions


def populateHeaderAliases(root,Message message) {
    def aliases = []
    def aliasTypeId = message.getProperty("aliasTypeId")
    def externalId = root.SalesOrder.ExternalDocumentID?.text()
    def sapSalesOrderID = root.SalesOrder.SalesOrderID?.text()

    if (externalId && externalId.trim()) {
        aliases << [
            aliasId  : externalId,
            aliasType: "EXTERNAL_ID"
        ]
    }

    if (sapSalesOrderID && sapSalesOrderID.trim()) {
        aliases << [
            aliasId  : sapSalesOrderID,
            aliasType: aliasTypeId
        ]
    }

    return aliases
}

//TBD: In actual implementation, this should come from S4
def populateShopperIdentity(Message message) {
    def lwaAccessTokenValue = message.getProperty("lwaAccessTokenValue")
    if (!lwaAccessTokenValue || lwaAccessTokenValue.trim() == "") {
        return null
    }
    
    return [
        lwaAccessToken: [
            value: lwaAccessTokenValue
        ]
    ]
}

def populateCustomer(root, Message message) {
    def partners = root.SalesOrder.Partner
    def partnerFunction = message.getProperty("partnerFunction")
    // Find Partner with PartnerFunction configured
    def recipientPartner = partners.find { it.PartnerFunction.text() == partnerFunction }
    
    def emailAddress = recipientPartner?.Address?.Communication?.EmailAddress?.text()
    def partnerName = recipientPartner?.Address?.AddressName?.text()
    def customerId = recipientPartner?.Customer?.text()

    if (emailAddress && emailAddress.trim()) { 
        def contactMap = [:]
        def emailDataMap = [:]

        emailDataMap.email = emailAddress
        if (partnerName && partnerName.trim()) {
            emailDataMap.name = partnerName
        }

        contactMap.emailData = emailDataMap

        def result = [ contact: contactMap ]
        if (customerId && customerId.trim()) {
            result.id = customerId
        }

        return result
    } else {
        throw new Exception("Mandatory field 'EmailAddress' is missing or blank.")
    }
}



def populateDesiredExecutionState(root, Message message) {
    def PLANT_CODE = message.getProperty("amazonBwPPlantCode")
    def headerBlockReason = (root.SalesOrder.DeliveryBlockReason?.text() ?: "").trim()
    if (headerBlockReason) {
        return "NOT_STARTED"
    }

    def items = root.SalesOrder.Item
    if (!items || items.size() == 0) {
        return "STARTED"
    }

    // Filter items where Plant matches PLANT_CODE
    def matchingItems = items.findAll { item ->
        item.Plant.text() == PLANT_CODE
    }

    // If no matching items, default to STARTED
    if (matchingItems.isEmpty()) {
        return "STARTED"
    }

    def allEmpty = matchingItems.every { item ->
        (item.ScheduleLine.DelivBlockReasonForSchedLine?.text() ?: "").trim() == ""
    }

    def allNonEmpty = matchingItems.every { item ->
        (item.ScheduleLine.DelivBlockReasonForSchedLine?.text() ?: "").trim() != ""
    }

    if (allEmpty) {
        return "STARTED"
    } else if (allNonEmpty) {
        return "NOT_STARTED"
    } else {
        throw new Exception("Mixed 'DelivBlockReasonForSchedLine' values found for items with matching Plant code - cannot determine execution state")
    }
}



def populateHeaderTaxSummary(root, Message message) {
    def conditionTypeTaxHeader = message.getProperty("conditionTypeTaxHeader")
    //If condition type for tax is not provided, use default TaxAmount field
    if (!conditionTypeTaxHeader)
    {
        def totalAmountText = root.SalesOrder.TotalTaxAmount.text()
        def totalCurrencyCode = root.SalesOrder.TotalTaxAmount.@currencyCode.text()?.trim()
        [
            summary: [
                collectableTaxAmount: [
                    amount: totalAmountText.toDouble(),
                    currencyCode: totalCurrencyCode
                ]
            ]
        ]
    }
    else{

        def headerTaxElement = root.SalesOrder.PricingElement.find { pe -> pe.ConditionType.text() == conditionTypeTaxHeader }
        if (!headerTaxElement) return null

        def amountText = headerTaxElement.ConditionAmount.text()?.trim()
        def currencyCode = headerTaxElement.ConditionAmount.@currencyCode.text()?.trim()
        if (!amountText || !currencyCode) return null

        [
            summary: [
                collectableTaxAmount: [
                    amount: amountText.toDouble(),
                    currencyCode: currencyCode
                ]
            ]
        ]
    }
}


def populateHeaderTotalPrice(root) {
    def currencyCode = root.SalesOrder.TransactionCurrency.text()
    def amountText = root.SalesOrder.TotalNetAmount.text()

    if (!amountText || amountText.trim() == "" || !currencyCode || currencyCode.trim() == "") {
        return null
    }

    def amount = amountText.toDouble()
    [
        value: [
            amount: amount,
            currencyCode: currencyCode
        ]
    ]
}

def populateHeaderDiscounts(root, Message message) {
    def conditionTypeDiscountHeader = message.getProperty("conditionTypeDiscountHeader")

    def headerDiscount = root.SalesOrder.PricingElement.find { pe -> pe.ConditionType.text() == conditionTypeDiscountHeader }
    if (!headerDiscount) return null

    def discountAmount = headerDiscount.ConditionAmount.text()?.trim()
    def discountcurrencyCode = headerDiscount.ConditionAmount.@currencyCode.text()?.trim()
    if (!discountAmount || !discountcurrencyCode) {
        return null
    }
    
    def positiveDiscountAmountText = discountAmount.replace("-", "");
    
    [   
            summary: [
                amount: [
                    amount: positiveDiscountAmountText.toDouble(),
                    currencyCode: discountcurrencyCode
                ]
            ]
        
    ]
}


def populateHeaderRecipient(root, Message message) {
    def partners = root.SalesOrder.Partner
    def partnerFunction = message.getProperty("partnerFunction")

    if (!partners || partners.size() == 0) {
        throw new Exception("No 'Partner' elements found in SalesOrder.")
    }

    // Find Partner with PartnerFunction configured
    def recipientPartner = partners.find { it.PartnerFunction.text() == partnerFunction }
    if (!recipientPartner) {
        throw new Exception("No 'Partner' with configured PartnerFunction value found.")
    }

    // Extract values directly from Partner
    def recipientName  = recipientPartner?.Address?.AddressName?.text()
    def contactNum     = recipientPartner?.Address?.Communication?.Phone?.PhoneNumber?.text()
    def street         = recipientPartner?.Address?.PhysicalAddress?.StreetName?.text()
    def city           = recipientPartner?.Address?.PhysicalAddress?.CityName?.text()
    def region         = recipientPartner?.Address?.PhysicalAddress?.Region?.text()
    def postal         = recipientPartner?.Address?.PhysicalAddress?.PostalCode?.text()
    def country        = recipientPartner?.Address?.PhysicalAddress?.Country?.text()

    // Validation for Required Fields
    if (!country || !country.trim()) {
        throw new Exception("Mandatory field 'Country' is missing or blank.")
    }
    if (!city || !city.trim()) {
        throw new Exception("Mandatory field 'locality' is missing or blank.")
    }
    if (!recipientName || !recipientName.trim()) {
        throw new Exception("Mandatory field 'name' is missing or blank.")
    }
    if (!street || !street.trim()) {
        throw new Exception("Mandatory field 'StreetName' is missing or blank.")
    }
    if (!region || !region.trim()) {
        throw new Exception("Mandatory field 'region' is missing or blank.")
    }
    if (!postal || !postal.trim()) {
        throw new Exception("Mandatory field 'postalCode' is missing or blank.")
    }

    // Only include optional fields if present and non-blank
    def result = [:]
    result['deliveryAddress'] = [:]
    result['deliveryAddress']['countryCode']    = country
    result['deliveryAddress']['locality']       = city
    result['deliveryAddress']['name']           = recipientName
    result['deliveryAddress']['streetAddress']  = street
    result['deliveryAddress']['region']         = region
    result['deliveryAddress']['postalCode']     = postal

    if (contactNum && contactNum.trim())
        result['deliveryAddress']['contactNumber'] = contactNum

    return result
}



def populatePayments(Message message) {
    def headerPaymentDetailsStr = message.getProperty("headerPaymentDetails")
    if (!headerPaymentDetailsStr || headerPaymentDetailsStr.trim() == "") {
        return null
    }
    // Parse the JSON string to ensure it's valid JSON
    def jsonSlurper = new JsonSlurper()
    def headerPaymentDetails = jsonSlurper.parseText(headerPaymentDetailsStr)
    return headerPaymentDetails
}

//----------------------------------------------------------------------------------------------------------------------------//

// Item JSON parts
def populateLineItems(root, Message message) {
    def items = root.SalesOrder.Item
    if (!items || items.size() == 0) {
        throw new Exception("No 'Item' segments found in the SalesOrder XML. At least one line item is required.")
    }

    return items.collect { item ->
        def lineItem = [:]

        def aliases = populateAliases(item)
        if (aliases != null) lineItem.aliases = aliases

        def product = populateProduct(item, message)
        if (product != null) lineItem.product = product

        def amount = populateAmount(item)
        if (amount != null) lineItem.amount = amount

        def discounts = populateDiscounts(item, message)
        if (discounts != null) lineItem.discounts = discounts

        def selectedDeliveryOffer = populateSelectedDeliveryOffer(item, message)
        if (selectedDeliveryOffer != null) lineItem.selectedDeliveryOffer = selectedDeliveryOffer

        def taxes = populateTaxSummary(item, message)
        if (taxes != null) lineItem.taxes = taxes

        lineItem
    }
}


//Item functions
def populateAliases(item) {
    [
        [
            aliasId: item.SalesOrderItemID.text(),
            aliasType: "EXTERNAL_ID"
        ]
    ]
}

def populateProduct(item, Message message) {
    def priceData = getPriceAndCurrency(item, message)
    def material = item.Material.text()
    def title = item.SalesOrderItemText.text()

    def productJson = [
        identifier: [
            externalId: material
        ],
        title: title
    ]

    if (priceData != null) {
        // Only add price element if valid price data exists
        productJson.price = [
            amount: priceData.amount,
            currencyCode: priceData.currencyCode
        ]
    }
    return productJson
}



def populateAmount(item) {
    def value = item.RequestedQuantity.text()
    if (!value || value.trim() == "") {
        throw new Exception("Quantity is null. amount cannot be null for item with SalesOrderItemID: " + item.SalesOrderItemID.text())
    }
    def unit = item.RequestedQuantity.@unitCode.text()
    def result = [value: value]
    if (unit && unit.trim() != "") {
        result.unit = unit
    }
    return result
}

def populateDiscounts(item, Message message) {
    def discountData = getDiscountAndCurrency(item, message)
    if (discountData == null) {
        // No discount data present
        return null
    }
 [   
            summary: [
                amount: [
                    amount: discountData.amount,
                    currencyCode: discountData.currencyCode
                ]
            ]
        
    ]
}


def populateSelectedDeliveryOffer(item, Message message) {
    
    def PLANT_CODE = message.getProperty("amazonBwPPlantCode")   // "1710"
    def delivery_speed = message.getProperty("amazonBwPDeliverySpeed") // "STANDARD"
    def isPrimeEligible = message.getProperty("amazonBwPIsPrimeEligible") // "false"
    def material = item.Material.text()// for isPrimeEligible
    
    if (PLANT_CODE == null || PLANT_CODE.trim() == "") {
        throw new Exception("Property 'amazonBwPPlantCode' is not set. Please fill this property in the integration flow configuration.")
    }
    
    def plant = item.Plant.text()
    if (!plant || plant.trim() == "") {
        throw new Exception("Plant is null. selectedDeliveryOffer cannot be null for item with SalesOrderItemID: " + item.SalesOrderItemID.text())
    }
    
    def deliveryProvider
    if (plant == PLANT_CODE) {
        deliveryProvider = "AMAZON"
    } else {
        deliveryProvider = "MERCHANT"
    }
    
    [
            details: [
                deliveryProvider: deliveryProvider,
                deliveryTerms: [
                    deliverySpeed: delivery_speed,
                    isPrimeEligible: isPrimeEligible.toBoolean()
                ]
            ]
        ]
}


def populateTaxSummary(item, Message message) {
    def taxData = getTaxAndCurrency(item, message)
    if (taxData == null) {
        return null
    }
    [
        summary: [
            collectableTaxAmount: [
                amount: taxData.amount,
                currencyCode: taxData.currencyCode
            ]
        ]
    ]
}

def getPriceAndCurrency(item, Message message) {
    def CONDITION_TYPE_PRICE = message.getProperty("conditionTypePriceItem")
    def priceElement = item.PricingElement.find { pe -> pe.ConditionType.text() == CONDITION_TYPE_PRICE }
    if (priceElement) {
        def amountText = priceElement.ConditionRateValue.text()
        def currency = priceElement.ConditionAmount.@currencyCode.text()
        if (!amountText || amountText.trim() == "" || !currency || currency.trim() == "") {
            throw new Exception("Price amount is missing for condition type '" + CONDITION_TYPE_PRICE + "'.")
        }
        def amount = amountText.toDouble()
        if (amount == 0) {
            throw new Exception("Price amount is 0, which is not allowed.")
        }
        return [amount: amount, currencyCode: currency]
    } else {
        return null
    }
}

def getTaxAndCurrency(item, Message message) {
    def CONDITION_TYPE_TAX_ITEM = message.getProperty("conditionTypeTaxItem")

    //If condition type for tax is not provided, use default TaxAmount field
    if (!CONDITION_TYPE_TAX_ITEM)
    {
        def itemAmountTax = item.TaxAmount.text();
        def itemCurrencyCode = item.TaxAmount.@currencyCode.text()?.trim();
        def amount = itemAmountTax.toDouble();
        return [amount: amount, currencyCode: itemCurrencyCode];
    } else {
        def taxElement = item.PricingElement.find { pe -> pe.ConditionType.text() == CONDITION_TYPE_TAX_ITEM }
        if (taxElement) {
            def amountText = taxElement.ConditionAmount.text()
            def currency = taxElement.ConditionAmount.@currencyCode.text()
            if (!amountText || amountText.trim() == "" || !currency || currency.trim() == "") {
                return null
            }
            def amount = amountText.toDouble()
            return [amount: amount, currencyCode: currency]
        } else {
            return null
        }
    } 
}

def getDiscountAndCurrency(item, Message message) {
    def CONDITION_TYPE_DISCOUNT = message.getProperty("conditionTypeDiscountItem")
    def discountElement = item.PricingElement.find { pe -> pe.ConditionType.text() == CONDITION_TYPE_DISCOUNT }
    if (discountElement) {
        def amountText = discountElement.ConditionAmount.text()
        def currency = discountElement.ConditionAmount.@currencyCode.text()
        if (!amountText || amountText.trim() == "" || !currency || currency.trim() == "") {
            return null
        }
        def positiveDiscountAmountText = amountText.replace("-", "")
        def amount = positiveDiscountAmountText.toDouble()
        return [amount: amount, currencyCode: currency]
    } else {
        return null
    }
}

