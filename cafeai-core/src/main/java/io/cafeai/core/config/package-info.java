/**
 * Application configuration contracts for CafeAI.
 *
 * <p>{@link io.cafeai.core.config.ConfigKey} declares a configuration value —
 * name, type, default, description — right at the point of use.
 * {@link io.cafeai.core.config.AppConfig} resolves one: {@code ambient()} is
 * the zero-dependency layer (system properties, then environment variables);
 * {@code load()} additionally falls through to {@code cafeai-config}'s
 * file/profile layer via {@link io.cafeai.core.spi.ConfigProvider} when that
 * module is present.
 *
 * <p>No module that reads configuration needs to depend on
 * {@code cafeai-config} — only the application deciding whether to load
 * files at all does. Full rationale, precedence order, and the "config
 * supplies values, never wires capabilities" boundary are in
 * {@code docs/adr/ADR-012-application-config.md}.
 */
package io.cafeai.core.config;
