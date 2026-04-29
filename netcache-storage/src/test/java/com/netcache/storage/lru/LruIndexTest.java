package com.netcache.storage.lru;

import com.netcache.common.ByteKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LruIndexTest {
    @Test
    void evictsTouchedKeysAndTracksSize() {
        LruIndex index = new LruIndex();
        ByteKey first = ByteKey.copyOf(new byte[]{1});
        ByteKey second = ByteKey.copyOf(new byte[]{2});

        index.touch(first);
        index.touch(second);

        assertThat(index.size()).isEqualTo(2);
        assertThat(index.evictOne()).isIn(first, second);
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void removeDeletesKeysFromIndex() {
        LruIndex index = new LruIndex();
        ByteKey key = ByteKey.copyOf(new byte[]{1});

        index.touch(key);
        index.remove(key);

        assertThat(index.size()).isZero();
        assertThat(index.evictOne()).isNull();
    }
}
