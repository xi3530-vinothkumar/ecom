package com.ecom.platform.core;

import com.ecom.common.events.Money;

/** proto Money <-> event/domain Money. */
public final class MoneyMapper {

    public static com.ecom.common.proto.v1.Money toProto(Money money) {
        return com.ecom.common.proto.v1.Money.newBuilder()
                .setCurrencyCode(money.currencyCode())
                .setUnits(money.units())
                .setNanos(money.nanos())
                .build();
    }

    public static Money fromProto(com.ecom.common.proto.v1.Money proto) {
        return new Money(proto.getCurrencyCode(), proto.getUnits(), proto.getNanos());
    }

    private MoneyMapper() {}
}
