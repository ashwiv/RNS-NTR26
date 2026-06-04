package com.mobileum.ntr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Golden Master / characterization tests for {@link NtrConfigReader}.
 *
 * <p>Locks in the externally observable behavior of the C++ original
 * {@code CNTRConfigReader} from
 * {@code RNS-NTR/cpp/NTRCacheLibrary/NTRConfigReader.{h,cpp}}.
 *
 * <p>Each test below maps to one scenario observed in the C++ source:
 * <ul>
 *   <li>{@code GetBOOL} when underlying reader is null → returns FALSE</li>
 *   <li>{@code GetBOOL} when underlying reader is set  → delegates to it</li>
 *   <li>{@code GetInt}  when underlying reader is null → returns -1</li>
 *   <li>{@code GetInt}  when underlying reader is set  → delegates to it</li>
 *   <li>{@code Initialize} responsibility moved to the {@link ConfigSource}
 *       implementation; the wrapper no longer owns file bootstrap</li>
 * </ul>
 */
class NtrConfigReaderGoldenMasterTest {

    private static ConfigSource of(Map<String, String> entries) {
        Map<String, String> copy = Map.copyOf(entries);
        return key -> Optional.ofNullable(copy.get(key));
    }

    private static ConfigSource empty() {
        return key -> Optional.empty();
    }

    @Nested
    @DisplayName("getBool / getBoolOrDefault — parity with C++ GetBOOL")
    class BooleanReads {

        @Test
        @DisplayName("absent key → getBoolOrDefault returns the default (C++: returns FALSE)")
        void absentKey_returnsDefault() {
            NtrConfigReader reader = new NtrConfigReader(empty());
            assertThat(reader.getBoolOrDefault("brg.zone.rej.limit.cache.in.shm", false)).isFalse();
        }

        @Test
        @DisplayName("absent key → getBool returns Optional.empty()")
        void absentKey_returnsEmpty() {
            NtrConfigReader reader = new NtrConfigReader(empty());
            assertThat(reader.getBool("any.key")).isEmpty();
        }

        @Test
        @DisplayName("value \"true\"  → getBool returns Optional.of(true)")
        void presentKey_true() {
            NtrConfigReader reader = new NtrConfigReader(of(Map.of("maintain.sys.state", "true")));
            assertThat(reader.getBool("maintain.sys.state")).contains(true);
        }

        @Test
        @DisplayName("value \"false\" → getBool returns Optional.of(false)")
        void presentKey_false() {
            NtrConfigReader reader = new NtrConfigReader(of(Map.of("maintain.sys.state", "false")));
            assertThat(reader.getBool("maintain.sys.state")).contains(false);
        }

        @Test
        @DisplayName("present key → getBoolOrDefault returns the parsed value, not the default")
        void presentKey_overridesDefault() {
            NtrConfigReader reader = new NtrConfigReader(of(Map.of("flag.x", "true")));
            assertThat(reader.getBoolOrDefault("flag.x", false)).isTrue();
        }
    }

    @Nested
    @DisplayName("getInt / getIntOrDefault — parity with C++ GetInt")
    class IntegerReads {

        @Test
        @DisplayName("absent key → getIntOrDefault returns the default (C++: returns -1)")
        void absentKey_returnsDefault() {
            NtrConfigReader reader = new NtrConfigReader(empty());
            assertThat(reader.getIntOrDefault("srdc.shm.arr.key", -1)).isEqualTo(-1);
        }

        @Test
        @DisplayName("absent key → getInt returns Optional.empty()")
        void absentKey_returnsEmpty() {
            NtrConfigReader reader = new NtrConfigReader(empty());
            assertThat(reader.getInt("any.int.key")).isEmpty();
        }

        @Test
        @DisplayName("present, parseable value → getInt returns Optional.of(value)")
        void presentKey_parseable() {
            NtrConfigReader reader = new NtrConfigReader(of(Map.of("sys.state.arr.key.1", "12345")));
            assertThat(reader.getInt("sys.state.arr.key.1")).contains(12345);
        }

        @Test
        @DisplayName("present but unparseable value → getInt returns Optional.empty()")
        void presentKey_unparseable_returnsEmpty() {
            NtrConfigReader reader = new NtrConfigReader(of(Map.of("bad.int", "not-a-number")));
            assertThat(reader.getInt("bad.int")).isEmpty();
        }

        @Test
        @DisplayName("present but unparseable value → getIntOrDefault returns the default")
        void presentKey_unparseable_returnsDefault() {
            NtrConfigReader reader = new NtrConfigReader(of(Map.of("bad.int", "xyz")));
            assertThat(reader.getIntOrDefault("bad.int", -1)).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("Constructor — null safety (CLAUDE.md P1)")
    class ConstructorSafety {

        @Test
        @DisplayName("rejects null ConfigSource")
        void nullSource_throws() {
            assertThatThrownBy(() -> new NtrConfigReader(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("configSource");
        }
    }
}
