package com.cenrs.contract;

public record RequestKey(
    String senderId,
    long requestNumber
) {
    public RequestKey {
        senderId = ContractValidation.requiredText(
            senderId,
            "sender ID",
            128
        );
        requestNumber = ContractValidation.positive(
            requestNumber,
            "request number"
        );
    }
}