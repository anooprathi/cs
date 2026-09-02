package com.schwab.urlshortener.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62EncoderTest {

    @Test
    void encode_zero_returnsFirstAlphabetChar() {
        assertThat(Base62Encoder.encode(0)).isEqualTo("0");
    }

    @Test
    void encode_negativeValue_throws() {
        assertThatThrownBy(() -> Base62Encoder.encode(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 61, 62, 1000, 123456789L, Long.MAX_VALUE})
    void encodeThenDecode_roundTrips(long value) {
        String encoded = Base62Encoder.encode(value);
        long decoded = Base62Encoder.decode(encoded);
        assertThat(decoded).isEqualTo(value);
    }

    @Test
    void decode_invalidCharacter_throws() {
        assertThatThrownBy(() -> Base62Encoder.decode("abc!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encode_isMonotonicForIncreasingValues_ofSameLength() {
        String a = Base62Encoder.encode(1000);
        String b = Base62Encoder.encode(1001);
        assertThat(a).isNotEqualTo(b);
    }
}
