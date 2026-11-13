package com.ecom.cart.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecom.cart.domain.CartOwner;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import com.ecom.platform.core.ErrorCode;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CartOwnerResolverTest {

    @Test
    void authenticatedUserWinsOverGuestHeader() {
        CartOwner owner = CallContext.call(new CallContext("c", "user-1", Set.of()),
                () -> CartOwnerResolver.resolve(UUID.randomUUID().toString()));
        assertThat(owner).isEqualTo(new CartOwner.User("user-1"));
        assertThat(new CartOwner.User("user-1").redisKey()).isEqualTo("cart:user:user-1");
    }

    @Test
    void guestHeaderIdentifiesAnonymousCart() {
        UUID id = UUID.randomUUID();
        CartOwner owner = CartOwnerResolver.resolve(id.toString());
        assertThat(owner).isEqualTo(new CartOwner.Guest(id));
        assertThat(owner.redisKey()).isEqualTo("cart:guest:" + id);
    }

    @Test
    void neitherIdentityIsBadRequest() {
        assertThatThrownBy(() -> CartOwnerResolver.resolve(null))
                .isInstanceOfSatisfying(EcomException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION));
    }

    @Test
    void malformedGuestIdIsBadRequest() {
        for (String bad : new String[] {"nope", "1-1-1-1-1", "  "}) {
            assertThatThrownBy(() -> CartOwnerResolver.resolve(bad))
                    .isInstanceOfSatisfying(EcomException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION));
        }
    }
}
