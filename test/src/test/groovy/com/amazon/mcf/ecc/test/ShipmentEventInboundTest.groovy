package com.amazon.mcf.ecc.test

import com.sap.gateway.ip.core.customdev.util.Message
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

import static org.junit.jupiter.api.Assertions.*

/**
 * INBOUND (Amazon MCF -> SAP ECC): Shipment event.
 *
 * Exercises CustomScripts.groovy (Fulfillment flow) offline using the Fulfillment
 * Outbound API 2020-07-01 FulfillmentOrderStatusNotification (EventType "Shipment")
 * sample shipment.json:
 *
 *   processData                -> status guard + delivery doc + carrier/tracking;
 *                                 BAPI_DELIVERY_GETLIST RFC XML
 *   validatePartialFulfillment -> parses delivery lines/qty from the GETLIST response
 *                                 (the notification carries no line items)
 *   buildPickAllItemsRequest   -> WS_DELIVERY_UPDATE (confirm picking) from delivery lines
 *   buildPostGoodsIssue        -> WS_DELIVERY_UPDATE (post Goods Issue) from delivery lines
 *
 * Only a "Complete" order is processed. "COMPLETE_PARTIAL" (partial shipment) and
 * "PROCESSING" (multiple shipments) are rejected in processData.
 *
 * No RFC call is made. The BAPI_DELIVERY_GETLIST "response" is supplied from a
 * local fixture so picking/GI XML can be produced offline.
 *
 * Note: processData reads message property "originalPayload" (set by an upstream
 * content-modifier step in the iFlow), so the test sets it explicitly.
 */
class ShipmentEventInboundTest {

    private Object script
    private Message msg

    private void primeShipment(String sampleFile) {
        script = TestSupport.loadScript('fulfillment')
        def payload = TestSupport.readSample(sampleFile)
        msg = TestSupport.newMessage(payload)
        // The iFlow stores the raw event in property originalPayload before processData runs.
        msg.setProperty('originalPayload', payload)
        script.processData(msg)
    }

    @Test
    @DisplayName("Shipment (Complete) extracts delivery doc + carrier/tracking, builds GETLIST")
    void extractsAndBuildsGetList() {
        primeShipment('shipment.json')

        assertEquals('0080018897', msg.getProperty('expectedDeliveryDoc'), 'delivery doc (padded)')
        assertEquals('FEDEX', msg.getProperty('carrierCode'), 'carrierCode from FulfillmentShipmentPackages[0]')
        assertEquals('123456789', msg.getProperty('trackingNumber'), 'trackingNumber from FulfillmentShipmentPackages[0]')
        assertEquals('DZRSmwG2N', msg.getProperty('amazonShipmentId'), 'AmazonShipmentId from FulfillmentShipment')

        def xml = msg.getBody(String.class)
        assertTrue(xml.contains('<rfc:BAPI_DELIVERY_GETLIST'), 'GETLIST RFC name')
        assertTrue(xml.contains('<DELIV_NUMB_LOW>0080018897</DELIV_NUMB_LOW>'), 'delivery doc in GETLIST')
    }

    @Test
    @DisplayName("Complete order -> picking + Goods Issue RFC produced from delivery lines")
    void producesPickingAndGoodsIssue() {
        primeShipment('shipment.json')

        // Supply the delivery GETLIST response (lines 10 & 20, qty 1.000).
        // validatePartialFulfillment parses these into the deliveryLines property.
        msg.setBody(TestSupport.readSample('SampleShipmentDelivery_Match.xml'))
        script.validatePartialFulfillment(msg)  // must not throw

        // Picking confirmation — compare against expected fixture (dates masked)
        script.buildPickAllItemsRequest(msg)
        def pickXml = msg.getBody(String.class)
        assertEquals(
            TestSupport.normalizeXml(TestSupport.readExpected('expected_shipment_picking.xml')),
            TestSupport.normalizeXml(TestSupport.maskTimestamps(pickXml)),
            "Picking WS_DELIVERY_UPDATE XML did not match expected.\nProduced:\n${pickXml}")

        // Post Goods Issue — compare against expected fixture (dates masked)
        script.buildPostGoodsIssue(msg)
        def giXml = msg.getBody(String.class)
        assertEquals(
            TestSupport.normalizeXml(TestSupport.readExpected('expected_shipment_goods_issue.xml')),
            TestSupport.normalizeXml(TestSupport.maskTimestamps(giXml)),
            "Goods Issue WS_DELIVERY_UPDATE XML did not match expected.\nProduced:\n${giXml}")
    }

    @Test
    @DisplayName("COMPLETE_PARTIAL shipment is rejected (partial shipment not supported)")
    void rejectsCompletePartial() {
        script = TestSupport.loadScript('fulfillment')
        def payload = TestSupport.readSample('shipment_partial.json')
        msg = TestSupport.newMessage(payload)
        msg.setProperty('originalPayload', payload)

        def ex = assertThrows(Exception.class, { script.processData(msg) })
        assertTrue(ex.message.contains('COMPLETE_PARTIAL'),
            "expected COMPLETE_PARTIAL rejection, got: ${ex.message}")
    }

    @Test
    @DisplayName("PROCESSING shipment is rejected (multiple shipments not supported)")
    void rejectsProcessing() {
        script = TestSupport.loadScript('fulfillment')
        def payload = TestSupport.readSample('shipment_processing.json')
        msg = TestSupport.newMessage(payload)
        msg.setProperty('originalPayload', payload)

        def ex = assertThrows(Exception.class, { script.processData(msg) })
        assertTrue(ex.message.contains('PROCESSING'),
            "expected PROCESSING rejection, got: ${ex.message}")
    }
}
