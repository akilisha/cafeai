/**
 * Application configuration contracts for CafeAI.
 *
 * <p>{@link io.cafeai.core.config.ConfigKey} declares a configuration value —
 * a single dotted name, Spring/Helidon style, plus type, default, and
 * description — right at the point of use.
 * {@link io.cafeai.core.config.AppConfig#load()} resolves one: this package
 * resolves only a key's own coded default; every other source — system
 * property, environment variable, file — is
 * {@link io.cafeai.core.spi.ConfigProvider}'s job, in full, supplied by
 * {@code cafeai-config} when that module is present.
 *
 * <p>No module that reads configuration needs to depend on
 * {@code cafeai-config} — only the application deciding whether real
 * resolution is active at all does. Full rationale, including why this
 * package deliberately does *not* attempt its own environment-variable
 * name mapping, is in {@code docs/adr/ADR-012-application-config.md}.
 */
package io.cafeai.core.config;
