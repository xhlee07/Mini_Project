package com.ctfly.app.domain.payment

fun paymentMethodLabel(method: String): String =
    when (method.removePrefix("Test ")) {
        "TNG" -> "Touch ’n Go eWallet"
        "FPX" -> "Online banking (FPX)"
        "Card" -> "Debit / credit card"
        else -> method
    }

fun paymentReferenceLabel(reference: String): String =
    if (reference.startsWith("TEST-")) "CTFLY-${reference.removePrefix("TEST-")}" else reference

fun paymentVoidReason(reason: String): String =
    if (reason == "Simulated refund. No real money moved.") "Payment refunded." else reason

fun paymentMessageLabel(message: String): String =
    when (message) {
        "Use a customer account for test checkout." -> "Use a customer account to pay online."
        "Choose a test payment method." -> "Choose a payment method."
        "Test payment not found." -> "Payment not found."
        "Only successful test payments can be refunded." ->
            "Only completed payments can be refunded."
        "This service has already started or been fulfilled. Test refund unavailable." ->
            "This service has already started or been fulfilled. A refund is unavailable."
        "Use the test refund action for simulated payments." ->
            "Open payment details to request a refund."
        "Registered. Complete test checkout." -> "Registered. Complete checkout."
        else -> message
    }
