package com.ecom.common.events.payload;

/** No PII: Notification fetches contact details via UserService.GetUser at send time. */
public record UserRegisteredPayload(String userId) {}
