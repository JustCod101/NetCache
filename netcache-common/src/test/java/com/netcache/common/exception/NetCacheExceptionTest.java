package com.netcache.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetCacheExceptionTest {
    @Test
    void exposesStableErrorCode() {
        NetCacheException exception = new ProtocolException("bad magic");

        assertThat(exception.code()).isEqualTo("PROTOCOL_ERROR");
        assertThat(exception).hasMessage("bad magic");
    }

    @Test
    void rejectsBlankErrorCode() {
        assertThatThrownBy(() -> new NetCacheException(" ", "message"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
