package com.amazon.mcf.ecc.test

import com.sap.gateway.ip.core.customdev.util.Message
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

import static org.junit.jupiter.api.Assertions.*

/**
 * OUTBOUND (SAP ECC -> Amazon MCF): Create Fulfillment Order.
 *
 * Exercises the two-phase transformation in
 * generateGraphQLCreateOrderRequest.groovy entirely offline:
 *
 *   Phase 1 (extractDeliveryData):    SampleCreateOrder.xml (BAPI_DELIVERY_GETLIST)
 *                                     -> stores delivery data, emits address-fetch RFC
 *   Phase 2 (buildCreateOrderRequest): SampleAddressResponse.xml (BAPISDORDER address)
 *                                     -> emits SP-API createOrder JSON (v2026-07-04)
 *
 * The produced JSON is compared structurally against expected/expected_createOrder.json.
 * No SP-API call is made.
 */
class CreateOrderOutboundTest {

    @Test
    @DisplayName("Delivery + address input produces v2026-07-04 SP-API createOrder JSON")
    void producesExpectedCreateOrderJson() {
        def script = TestSupport.loadScript('createOrder')

        // --- Phase 1: parse the delivery GETLIST payload ---
        Message msg = TestSupport.newMessage(TestSupport.readSample('SampleDeliveryGetList.xml'))
        script.extractDeliveryData(msg)

        // Sanity: Phase 1 should have captured the delivery + sales order + WE ADRNR
        assertEquals('0080018863', msg.getProperty('deliveryNumber'), 'delivery number')
        assertEquals('0000021994', msg.getProperty('salesOrder'), 'sales order (VGBEL)')
        assertEquals('9000002419', msg.getProperty('shipToAddressNumber'), 'WE partner ADRNR')

        // --- Phase 2: feed the address response, build the SP-API JSON ---
        msg.setBody(TestSupport.readSample('SampleAddressResponse.xml'))
        script.buildCreateOrderRequest(msg)

        def produced = TestSupport.parseJson(msg.getBody(String.class))
        def expected = TestSupport.parseJson(TestSupport.readExpected('expected_createOrder.json'))

        assertEquals(expected, produced,
            "Produced SP-API createOrder JSON did not match expected fixture.\nProduced: ${msg.getBody(String.class)}")
    }

    @Test
    @DisplayName("createOrder JSON uses the v2026-07-04 fulfillmentConfiguration structure")
    void usesGaFulfillmentConfiguration() {
        def script = TestSupport.loadScript('createOrder')
        Message msg = TestSupport.newMessage(TestSupport.readSample('SampleDeliveryGetList.xml'))
        script.extractDeliveryData(msg)
        msg.setBody(TestSupport.readSample('SampleAddressResponse.xml'))
        script.buildCreateOrderRequest(msg)

        def json = TestSupport.parseJson(msg.getBody(String.class))

        // v2026-07-04 structure: fulfillmentConfiguration.serviceLevel.serviceTiers (array),
        // action + policy nested (NOT top-level deliveryServiceLevel/fulfillmentAction).
        assertNotNull(json.fulfillmentConfiguration, 'fulfillmentConfiguration present')
        assertNull(json.deliveryServiceLevel, 'no top-level deliveryServiceLevel (beta field)')
        assertNull(json.fulfillmentAction, 'no top-level fulfillmentAction (beta field)')
        assertEquals(['STANDARD'], json.fulfillmentConfiguration.serviceLevel.serviceTiers, 'serviceTiers array')
        assertEquals('SHIP', json.fulfillmentConfiguration.action, 'action')
        assertEquals('FILL_ALL_AVAILABLE', json.fulfillmentConfiguration.policy, 'policy')

        // orderId pattern <SalesOrder>.<DeliveryDoc>
        assertEquals('0000021994.0080018863', json.orderId, 'orderId = SO.DeliveryDoc')
        assertEquals('0000021994', json.displayableOrderId, 'displayableOrderId = SO')

        // Line item mapped from delivery: amazonSku = MATNR, unit EACHES
        assertEquals(1, json.lineItems.size(), 'one line item')
        assertEquals('TG61', json.lineItems[0].product.productIdentifier.amazonSku, 'amazonSku = MATNR')
        assertEquals('EACHES', json.lineItems[0].amount.unit, 'unit EACHES')
    }
}
