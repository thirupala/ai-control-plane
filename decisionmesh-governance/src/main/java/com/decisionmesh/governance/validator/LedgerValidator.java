package com.decisionmesh.governance.validator;

import com.decisionmesh.governance.ledger.LedgerEntry;
import com.decisionmesh.governance.hash.HashUtil;
import java.util.List;

public class LedgerValidator {

    public void validateChain(List<LedgerEntry> entries) {
        String previousHash = "GENESIS";

        for (LedgerEntry entry : entries) {
            String recomputed = HashUtil.sha256(entry.computeDeterministicPayload());
            if (!recomputed.equals(entry.getCurrentHash()))
                throw new IllegalStateException("Ledger hash mismatch");

            if (!entry.getPreviousHash().equals(previousHash))
                throw new IllegalStateException("Ledger chain broken");

            previousHash = entry.getCurrentHash();
        }
    }
}