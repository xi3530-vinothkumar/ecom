CREATE TABLE notification_templates (
    key     VARCHAR(64)  PRIMARY KEY,
    channel VARCHAR(16)  NOT NULL CHECK (channel IN ('EMAIL')),
    subject VARCHAR(255) NOT NULL,
    body    TEXT         NOT NULL
);

-- Audit of every send attempt. recipient is masked: contact details live only in userdb (GDPR erasure there).
CREATE TABLE notification_log (
    id               UUID         PRIMARY KEY,
    event_id         UUID         NOT NULL,
    user_id          VARCHAR(64)  NOT NULL,
    template_key     VARCHAR(64)  NOT NULL,
    channel          VARCHAR(16)  NOT NULL,
    recipient_masked VARCHAR(255),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('SENT', 'FAILED')),
    error            VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_notification_log_event ON notification_log (event_id);
CREATE INDEX idx_notification_log_user_created ON notification_log (user_id, created_at DESC);

INSERT INTO notification_templates (key, channel, subject, body) VALUES
('WELCOME', 'EMAIL',
 'Welcome to Ecom',
 E'Hi ${name},\n\nThanks for creating your Ecom account. Happy shopping!\n\nThe Ecom team'),
('ORDER_CONFIRMED', 'EMAIL',
 'Your order ${orderId} is confirmed',
 E'Hi ${name},\n\nThanks for your order. We received your payment of ${total} for order ${orderId} (${itemCount} item(s)) and are getting it ready.\n\nThe Ecom team'),
('ORDER_CANCELLED_OUT_OF_STOCK', 'EMAIL',
 'Your order ${orderId} was cancelled',
 E'Hi ${name},\n\nUnfortunately one or more items in order ${orderId} went out of stock, so we cancelled the order. You have not been charged.\n\nThe Ecom team'),
('ORDER_CANCELLED_PAYMENT_DECLINED', 'EMAIL',
 'Your order ${orderId} was cancelled',
 E'Hi ${name},\n\nYour payment for order ${orderId} was declined, so we cancelled the order. You have not been charged. You are welcome to try again with another payment method.\n\nThe Ecom team'),
('ORDER_CANCELLED_PAYMENT_TIMEOUT', 'EMAIL',
 'Your order ${orderId} was cancelled',
 E'Hi ${name},\n\nWe did not receive payment confirmation for order ${orderId} in time, so we cancelled the order and released the items. Any pending authorization on your card will be released.\n\nThe Ecom team');
