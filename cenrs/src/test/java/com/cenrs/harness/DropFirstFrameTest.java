package com.cenrs.harness;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DropFirstFrameTest {

    @Test
    public void dropsOnlyTheFirstMatchingAck() {
        String aliceAck = "ALERT_ACK|83|INC102|1|Alice";
        String bobAck = "ALERT_ACK|84|INC102|1|Bob";

        DropFirstFrame rule = new DropFirstFrame(aliceAck);

        assertFalse(rule.shouldDrop(bobAck));   // Bob's ACK should pass.
        assertTrue(rule.shouldDrop(aliceAck));  // Drop Alice's first ACK.
        assertFalse(rule.shouldDrop(aliceAck)); // Let her second ACK pass.
    }
}