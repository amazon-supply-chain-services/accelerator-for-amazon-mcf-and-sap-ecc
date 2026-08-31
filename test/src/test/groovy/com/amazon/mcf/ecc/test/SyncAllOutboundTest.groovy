package com.amazon.mcf.ecc.test

import com.sap.gateway.ip.core.customdev.util.Message
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

import static org.junit.jupiter.api.Assertions.*

/**
 * OUTBOUND batch (SAP ECC -> Amazon MCF): Sync All Fulfillment Orders.
 *
 * Exercises SyncOrdersECC.groovy offline. Sync All periodically searches ECC
 * for eligible deliveries (by shipping point + creation-date range, picking
 * not yet started), then for each delivery checks an idempotency marker
 * (RFC_READ_TEXT) before creating the Amazon fulfillment order.
 *
 * Methods under test:
 *   buildGetDeliveryListRequest      -> BAPI_DELIVERY_GETLIST search request
 *   parseDeliveryListResponse        -> extracts VBELN list -> orderList / orderCount
 *   getCurrentDelivery               -> loop iterator over the CSV list
 *   buildCheckProcessedTextRequest   -> RFC_READ_TEXT idempotency probe
 *   parseTextAndBuildDeliveryRequest -> skipOrder (already processed) OR per-delivery GETLIST
 *
 * No RFC call is made — the BAPI_DELIVERY_GETLIST and RFC_READ_TEXT "responses"
 * are supplied from local fixtures so the batch selection/idempotency logic
 * can be exercised offline.
 */
class SyncAllOutboundTest {

    @Test
    @DisplayName("Search request carries shipping point, creation-date range, and picking-not-started filter")
    void buildsSearchRequest() {
        def script = TestSupport.loadScript('syncAll')
        Message msg = TestSupport.newMessage('')
        msg.setProperty('shippingPoint', '1201')
        msg.setProperty('creationDateFrom', '20260808')
        msg.setProperty('creationDateTo', '20260813')

        script.buildGetDeliveryListRequest(msg)
        def xml = msg.getBody(String.class)

        assertTrue(xml.contains('<rfc:BAPI_DELIVERY_GETLIST'), 'GETLIST RFC name')
        assertTrue(xml.contains('<SHIP_POINT_LOW>1201</SHIP_POINT_LOW>'), 'shipping point filter')
        assertTrue(xml.contains('<CR_ON_LOW>20260808</CR_ON_LOW>'), 'creation date from')
        assertTrue(xml.contains('<CR_ON_HIGH>20260813</CR_ON_HIGH>'), 'creation date to')
        assertTrue(xml.contains('<PKSTK_LOW>A</PKSTK_LOW>'), 'picking-not-started filter')
    }

    @Test
    @DisplayName("Multi-delivery GETLIST response is parsed into the delivery list")
    void parsesDeliveryList() {
        def script = TestSupport.loadScript('syncAll')
        Message msg = TestSupport.newMessage(TestSupport.readSample('SampleDeliverySearchResponse.xml'))

        script.parseDeliveryListResponse(msg)

        assertEquals('3', msg.getProperty('orderCount'), 'delivery count')
        assertEquals('0080018886,0080018887,0080018888', msg.getProperty('orderList'), 'delivery VBELN list')
    }

    @Test
    @DisplayName("Loop iterator walks the delivery list by index")
    void iteratesDeliveries() {
        def script = TestSupport.loadScript('syncAll')
        Message msg = TestSupport.newMessage(TestSupport.readSample('SampleDeliverySearchResponse.xml'))
        script.parseDeliveryListResponse(msg)

        script.getCurrentDelivery(msg)
        assertEquals('0080018886', msg.getProperty('currentOrder'), 'first delivery')
        assertEquals('1', msg.getProperty('loopIndex'), 'index advanced')

        script.getCurrentDelivery(msg)
        assertEquals('0080018887', msg.getProperty('currentOrder'), 'second delivery')

        script.getCurrentDelivery(msg)
        assertEquals('0080018888', msg.getProperty('currentOrder'), 'third delivery')
        assertEquals('3', msg.getProperty('loopIndex'), 'index at end')
    }

    @Test
    @DisplayName("Idempotency: delivery with existing header text is skipped")
    void skipsAlreadyProcessedDelivery() {
        def script = TestSupport.loadScript('syncAll')
        Message msg = TestSupport.newMessage('')
        msg.setProperty('currentOrder', '0080018887')

        // RFC_READ_TEXT response has a non-empty TDLINE -> already processed
        msg.setBody(TestSupport.readSample('SampleReadText_Processed.xml'))
        script.parseTextAndBuildDeliveryRequest(msg)

        assertEquals('true', msg.getProperty('skipOrder'),
            'delivery with existing text should be skipped')
    }

    @Test
    @DisplayName("Not processed: builds per-delivery GETLIST and does not skip")
    void processesNewDelivery() {
        def script = TestSupport.loadScript('syncAll')
        Message msg = TestSupport.newMessage('')
        msg.setProperty('currentOrder', '0080018887')

        // First, the idempotency probe request is well-formed
        script.buildCheckProcessedTextRequest(msg)
        def probe = msg.getBody(String.class)
        assertTrue(probe.contains('<rfc:RFC_READ_TEXT'), 'RFC_READ_TEXT probe')
        assertTrue(probe.contains('<TDNAME>0080018887</TDNAME>'), 'delivery in probe')

        // RFC_READ_TEXT response has empty TDLINE -> not processed -> build per-delivery GETLIST
        msg.setBody(TestSupport.readSample('SampleReadText_NotProcessed.xml'))
        script.parseTextAndBuildDeliveryRequest(msg)

        assertEquals('false', msg.getProperty('skipOrder'), 'new delivery should not be skipped')
        def xml = msg.getBody(String.class)
        assertTrue(xml.contains('<rfc:BAPI_DELIVERY_GETLIST'), 'per-delivery GETLIST built')
        assertTrue(xml.contains('<DELIV_NUMB_LOW>0080018887</DELIV_NUMB_LOW>'), 'delivery in per-delivery GETLIST')
    }
}
