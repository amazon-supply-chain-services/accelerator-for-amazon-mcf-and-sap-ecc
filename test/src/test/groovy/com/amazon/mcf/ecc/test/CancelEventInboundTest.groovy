package com.amazon.mcf.ecc.test

import com.sap.gateway.ip.core.customdev.util.Message
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

import static org.junit.jupiter.api.Assertions.*

/**
 * INBOUND (Amazon MCF -> SAP ECC): Cancel event.
 *
 * Exercises DeliveryCancelFunctions.groovy offline using the Fulfillment Outbound
 * API 2020-07-01 FulfillmentOrderStatusNotification (EventType "Order") sample
 * cancel.json:
 *
 *   processData                    -> status guard + SellerFulfillmentOrderId -> SO / delivery doc
 *   buildGetDeliveryListRequest    -> BAPI_DELIVERY_GETLIST RFC XML
 *   parseDeliveryStatusAndValidate -> GBSTK=A ECC guard; captures delivery lines
 *   buildZeroDeliveryQty           -> WS_DELIVERY_UPDATE RFC XML (LIPS_DEL=X per line)
 *
 * Only a full "Cancelled" order is processed. "COMPLETE_PARTIAL" (partial
 * cancellation) is rejected in processData.
 *
 * No RFC call is made — the BAPI_DELIVERY_GETLIST "response" is supplied from a
 * local fixture so the validation + zero-out XML can be produced offline.
 */
class CancelEventInboundTest {

    @Test
    @DisplayName("Cancelled order produces the zero-quantity WS_DELIVERY_UPDATE RFC XML")
    void producesZeroQtyRfc() {
        def script = TestSupport.loadScript('cancel')

        // Step 1: parse the cancel event
        Message msg = TestSupport.newMessage(TestSupport.readSample('cancel.json'))
        script.processData(msg)

        assertEquals('0000022026.0080018887', msg.getProperty('orderId'), 'orderId (SellerFulfillmentOrderId)')
        assertEquals('0000022026', msg.getProperty('salesOrder'), 'sales order (padded)')
        assertEquals('0080018887', msg.getProperty('deliveryDocumentNumber'), 'delivery doc (padded)')

        // Step 2: build GETLIST request (RFC XML) — assert structure
        script.buildGetDeliveryListRequest(msg)
        def getListXml = msg.getBody(String.class)
        assertTrue(getListXml.contains('<rfc:BAPI_DELIVERY_GETLIST'), 'GETLIST RFC name')
        assertTrue(getListXml.contains('<DELIV_NUMB_LOW>0080018887</DELIV_NUMB_LOW>'), 'delivery doc in GETLIST')

        // Step 3: supply a mock GETLIST response (delivery not processed, 1 line) and validate
        msg.setBody(TestSupport.readSample('SampleDeliveryStatus_NotProcessed.xml'))
        script.parseDeliveryStatusAndValidate(msg)  // must not throw

        // Step 4: build the zero-out WS_DELIVERY_UPDATE and compare against expected fixture
        script.buildZeroDeliveryQty(msg)
        assertEquals(
            TestSupport.normalizeXml(TestSupport.readExpected('expected_cancel_ws_delivery_update.xml')),
            TestSupport.normalizeXml(msg.getBody(String.class)),
            "Zero-qty WS_DELIVERY_UPDATE XML did not match expected.\nProduced:\n${msg.getBody(String.class)}")
    }

    @Test
    @DisplayName("COMPLETE_PARTIAL cancel is rejected (partial cancellation not supported)")
    void rejectsCompletePartialCancel() {
        def script = TestSupport.loadScript('cancel')
        Message msg = TestSupport.newMessage(TestSupport.readSample('cancel_partial.json'))

        def ex = assertThrows(Exception.class, { script.processData(msg) })
        assertTrue(ex.message.contains('COMPLETE_PARTIAL'),
            "expected COMPLETE_PARTIAL rejection, got: ${ex.message}")
    }

    @Test
    @DisplayName("Already-processed delivery (GBSTK != A) is rejected — no cancel")
    void rejectsAlreadyProcessedDelivery() {
        def script = TestSupport.loadScript('cancel')
        Message msg = TestSupport.newMessage(TestSupport.readSample('cancel.json'))
        script.processData(msg)
        script.buildGetDeliveryListRequest(msg)

        // Mock response where GBSTK != A (already processed)
        msg.setBody(TestSupport.readSample('SampleDeliveryStatus_Processed.xml'))

        def ex = assertThrows(Exception.class, { script.parseDeliveryStatusAndValidate(msg) })
        assertTrue(ex.message.contains('already processed'), "expected 'already processed' error, got: ${ex.message}")
    }
}
