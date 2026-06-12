package com.aiminilab.aitoolmarket.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Utf8TextRepairTest {

    @Test
    void repairsDoubleEncodedWorkflowTitle() {
        String garbled = "è\u201Ešæœ¬æ\u201E\u008Fè§\u0081";
        String repaired = Utf8TextRepair.repairIfNeeded(garbled);
        assertEquals("脚本意见", repaired);
    }

    @Test
    void leavesValidChineseUntouched() {
        String original = "脚本意见";
        assertEquals(original, Utf8TextRepair.repairIfNeeded(original));
    }

    @Test
    void repairsGarbledJsonSnippet() {
        String garbled = "[{\"data\":{\"title\":\"è\u201Ešæœ¬æ\u201E\u008Fè§\u0081\"}}]";
        String repaired = Utf8TextRepair.repairIfNeeded(garbled);
        assertTrue(repaired.contains("脚本意见"));
    }
}
