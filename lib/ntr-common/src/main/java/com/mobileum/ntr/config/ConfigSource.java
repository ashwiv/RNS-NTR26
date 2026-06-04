package com.mobileum.ntr.config;

import java.util.Optional;

/**
 * Source of raw string configuration values, keyed by name.
 *
 * <p>Abstracts over {@code fsmapp.properties}, environment variables, system
 * properties, or any other backing store. Allows {@link NtrConfigReader} to be
 * exercised in tests without filesystem dependencies (CLAUDE.md P1 — D in
 * SOLID, dependency inversion).
 *
 * <p>Architectural note: in the C++ source, file/SHM bootstrap was a
 * responsibility of {@code CNTRConfigReader::Initialize}. In the Java port,
 * that responsibility moves to the {@code ConfigSource} implementation,
 * keeping {@link NtrConfigReader} a pure read-side wrapper.
 */
@FunctionalInterface
public interface ConfigSource {

    /**
     * @param key the configuration key (e.g. {@code "brg.zone.rej.limit.cache.in.shm"})
     * @return the raw string value bound to {@code key}, or
     *         {@link Optional#empty()} if the key is not present.
     */
    Optional<String> get(String key);
}
