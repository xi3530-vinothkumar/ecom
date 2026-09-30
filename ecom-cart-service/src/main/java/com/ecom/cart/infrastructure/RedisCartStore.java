package com.ecom.cart.infrastructure;

import com.ecom.cart.config.CartProperties;
import com.ecom.cart.domain.CartMergePolicy;
import com.ecom.cart.domain.CartOwner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Cart hash per owner: field = SKU, value = quantity. Every multi-step mutation is a Lua script (or
 * WATCH/MULTI for merge) so concurrent requests and the order-confirmed consumer never interleave.
 */
@Repository
public class RedisCartStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCartStore.class);
    private static final int MERGE_ATTEMPTS = 5;

    /** KEYS[1]=cart; ARGV = sku, qty, ttlSeconds (0 = none), maxLines. Returns 0 if the line cap is hit. */
    private static final RedisScript<Long> SET_QTY = RedisScript.of("""
            if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 and redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[4]) then
              return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            if tonumber(ARGV[3]) > 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            return 1
            """, Long.class);

    /** KEYS[1]=cart; ARGV = sku, ttlSeconds (0 = none). */
    private static final RedisScript<Long> REMOVE = RedisScript.of("""
            local removed = redis.call('HDEL', KEYS[1], ARGV[1])
            if tonumber(ARGV[2]) > 0 and redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return removed
            """, Long.class);

    /** KEYS[1]=cart; ARGV = sku1, qty1, sku2, qty2, ... Lines reaching <= 0 are removed; absent SKUs ignored. */
    private static final RedisScript<Long> DECREMENT = RedisScript.of("""
            local changed = 0
            for i = 1, #ARGV, 2 do
              local cur = redis.call('HGET', KEYS[1], ARGV[i])
              if cur then
                local left = tonumber(cur) - tonumber(ARGV[i + 1])
                if left <= 0 then
                  redis.call('HDEL', KEYS[1], ARGV[i])
                else
                  redis.call('HSET', KEYS[1], ARGV[i], left)
                end
                changed = changed + 1
              end
            end
            return changed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final CartProperties props;

    public RedisCartStore(StringRedisTemplate redis, CartProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** SKU -> quantity, sorted by SKU. */
    public Map<String, Integer> lines(CartOwner owner) {
        return toQuantities(redis.<String, String>opsForHash().entries(owner.redisKey()));
    }

    /** @return false if the SKU is new and the cart already holds the maximum number of lines. */
    public boolean setQuantity(CartOwner owner, String sku, int quantity) {
        Long ok = redis.execute(SET_QTY, List.of(owner.redisKey()),
                sku, Integer.toString(quantity), ttlSeconds(owner), Integer.toString(props.maxLines()));
        return ok != null && ok == 1L;
    }

    public void remove(CartOwner owner, String sku) {
        redis.execute(REMOVE, List.of(owner.redisKey()), sku, ttlSeconds(owner));
    }

    public void clear(CartOwner owner) {
        redis.delete(owner.redisKey());
    }

    /** @return number of cart lines that were present and decremented. */
    public long decrement(CartOwner.User owner, Map<String, Integer> quantities) {
        if (quantities.isEmpty()) {
            return 0;
        }
        List<String> args = new ArrayList<>(quantities.size() * 2);
        quantities.forEach((sku, qty) -> {
            args.add(sku);
            args.add(Integer.toString(qty));
        });
        Long changed = redis.execute(DECREMENT, List.of(owner.redisKey()), args.toArray());
        return changed == null ? 0 : changed;
    }

    /**
     * Applies {@link CartMergePolicy} and deletes the guest cart atomically: optimistic WATCH on both
     * keys, retried if either changed between read and EXEC.
     */
    public Map<String, Integer> merge(CartOwner.Guest guest, CartOwner.User user) {
        for (int attempt = 1; attempt <= MERGE_ATTEMPTS; attempt++) {
            Map<String, Integer> merged = redis.execute(new SessionCallback<Map<String, Integer>>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Map<String, Integer> execute(RedisOperations ops) throws DataAccessException {
                    String guestKey = guest.redisKey();
                    String userKey = user.redisKey();
                    ops.watch(List.of(guestKey, userKey));
                    Map<String, Integer> guestLines = toQuantities(ops.opsForHash().entries(guestKey));
                    Map<String, Integer> userLines = toQuantities(ops.opsForHash().entries(userKey));
                    if (guestLines.isEmpty()) {
                        ops.unwatch();
                        return userLines;
                    }
                    Map<String, Integer> changes = CartMergePolicy.changesFor(userLines, guestLines, props.maxLines());
                    ops.multi();
                    if (!changes.isEmpty()) {
                        Map<String, String> encoded = new LinkedHashMap<>();
                        changes.forEach((k, v) -> encoded.put(k, Integer.toString(v)));
                        ops.opsForHash().putAll(userKey, encoded);
                    }
                    ops.delete(guestKey);
                    List<Object> result = ops.exec();
                    if (result == null || result.isEmpty()) {
                        return null; // a watched key changed: retry
                    }
                    Map<String, Integer> out = new TreeMap<>(userLines);
                    out.putAll(changes);
                    return out;
                }
            });
            if (merged != null) {
                return merged;
            }
            log.debug("Cart merge {} -> {} raced a concurrent write (attempt {})", guest, user, attempt);
        }
        throw new IllegalStateException("Cart merge kept conflicting with concurrent writes");
    }

    private String ttlSeconds(CartOwner owner) {
        return switch (owner) {
            case CartOwner.Guest _ -> Long.toString(props.guestTtl().toSeconds());
            case CartOwner.User _ -> "0";
        };
    }

    private static Map<String, Integer> toQuantities(Map<?, ?> raw) {
        Map<String, Integer> out = new TreeMap<>();
        raw.forEach((k, v) -> {
            try {
                out.put(String.valueOf(k), Integer.parseInt(String.valueOf(v)));
            } catch (NumberFormatException _) {
                log.warn("Ignoring malformed cart line {}={}", k, v);
            }
        });
        return out;
    }
}
