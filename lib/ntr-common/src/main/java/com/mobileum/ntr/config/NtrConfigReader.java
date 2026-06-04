package com.mobileum.ntr.config;

import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * (ported-cpp)
 *
 * <p>Java 21 port of {@code CNTRConfigReader} from
 * {@code RNS-NTR/cpp/NTRCacheLibrary/NTRConfigReader.{h,cpp}}.
 *
 * <p>Reads boolean and integer configuration values by name from an injected
 * {@link ConfigSource}. Returns {@link Optional#empty()} when a key is absent
 * or unparseable, mirroring the C++ wrapper's "default deny" posture for
 * unconfigured keys (which returned {@code FALSE} / {@code -1} sentinels).
 *
 * <p>Differences from the C++ original (intentional, per CLAUDE.md):
 * <ul>
 *   <li>Static singleton replaced with constructor injection (P1).</li>
 *   <li>{@code BOOL}/{@code -1} sentinels replaced with {@link Optional} (P1).</li>
 *   <li>File / SHM bootstrap responsibility moved to the {@link ConfigSource}
 *       implementation (P1 — single responsibility).</li>
 *   <li>Convenience {@code *OrDefault} methods preserve the C++ caller contract
 *       (return a fallback when the key is missing) for callers that prefer
 *       primitives over {@link Optional}.</li>
 * </ul>
 *
 * <p>Boolean parsing is strict: only the case-insensitive literals
 * {@code "true"} and {@code "false"} produce values; everything else yields
 * {@link Optional#empty()}. Integer parsing accepts any value parseable by
 * {@link Integer#valueOf(String)}; anything else yields
 * {@link Optional#empty()}.
 */
public final class NtrConfigReader {

    private static final Logger log = LogManager.getLogger(NtrConfigReader.class);

    private final ConfigSource configSource;

    public NtrConfigReader(ConfigSource configSource) {
        this.configSource = Objects.requireNonNull(configSource, "configSource must not be null");
    }

    public Optional<Boolean> getBool(String name) {
        return configSource.get(name).flatMap(raw -> parseBool(name, raw));
    }

    public boolean getBoolOrDefault(String name, boolean defaultValue) {
        return getBool(name).orElse(defaultValue);
    }

    public Optional<Integer> getInt(String name) {
        return configSource.get(name).flatMap(raw -> parseInt(name, raw));
    }

    public int getIntOrDefault(String name, int defaultValue) {
        return getInt(name).orElse(defaultValue);
    }

    private static Optional<Boolean> parseBool(String name, String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return Optional.of(Boolean.TRUE);
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Optional.of(Boolean.FALSE);
        }
        log.debug("Config value for key={} is not a parseable boolean: {}", name, raw);
        return Optional.empty();
    }

    private static Optional<Integer> parseInt(String name, String raw) {
        try {
            return Optional.of(Integer.valueOf(raw));
        } catch (NumberFormatException e) {
            log.debug("Config value for key={} is not a parseable integer: {}", name, raw);
            return Optional.empty();
        }
    }
}
