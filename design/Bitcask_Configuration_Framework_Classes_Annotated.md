# Bitcask Configuration Framework — Class Design (Annotated)

> Companion to `Bitcask_Configuration_Framework_Design.md`. Same class
> stubs as `Bitcask_Configuration_Framework_Classes.md`, with a plain-English
> "what it does" and a small usage example added under each class. No method
> bodies — implementation comes after this shape is agreed on.

---

## Table of contents

1. [Frozen property table](#frozen-property-table)
2. [Pipeline overview](#pipeline-overview)
3. [Package structure](#package-structure)
4. [`config.metadata`](#configmetadata)
5. [`config.converter`](#configconverter)
6. [`config.validation`](#configvalidation)
7. [`config.model`](#configmodel)
8. [`config.builder`](#configbuilder)
9. [`config.reader`](#configreader)
10. [`config.parser`](#configparser)
11. [`config.loader`](#configloader)
12. [`config.exception`](#configexception)
13. [Open design questions](#open-design-questions)

---

## Frozen property table

| Property | Type | Default | Required | Validation | Description |
|---|---|---|---|---|---|
| `storage.path` | Path | — | Yes | Must not be null/empty; must be a writable directory | Root directory where segment files live |
| `segment.size` | Size | 128MB | No | Must be > 0 | Threshold at which the active segment is sealed and rotated |
| `sync.strategy` | Enum (`NONE`, `EVERY_WRITE`, `INTERVAL`) | `NONE` | No | Must be one of the enum values | Controls fsync behavior on write |
| `sync.interval` | Duration | 1s | No (required if `sync.strategy=INTERVAL`) | Must be > 0 | How often to fsync when using interval strategy |
| `merge.auto.enabled` | Boolean | true | No | None | Whether background auto-merge runs at all |
| `merge.threshold` | Double (0.0–1.0) | 0.4 | No | Must be between 0 and 1 | Fraction of dead bytes that triggers merge eligibility |
| `merge.min.file.age` | Duration | 10m | No | Must be ≥ 0 | Minimum age before a sealed segment is eligible for merge |
| `merge.interval` | Duration | 5m | No | Must be > 0 | How often the merge scheduler checks eligible segments |
| `compression.enabled` | Boolean | false | No | None | Reserved — not yet wired to `LogRecord` |
| `cache.read.size` | Size | 0 (disabled) | No | Must be ≥ 0 | Reserved — read cache max memory |
| `buffer.write.size` | Size | 0 (disabled) | No | Must be ≥ 0 | Reserved — in-memory write buffer size |
| `file.permissions` | String (POSIX mode) | `rw-r-----` | No | Must be a valid POSIX permission string | Permissions for created segment files |
| `key.max.size` | Size | 1KB | No | Must be > 0 | Max allowed key size in bytes |
| `value.max.size` | Size | 1MB | No | Must be > 0 | Max allowed value size in bytes |
| `recovery.use.hint.file` | Boolean | true | No | None | Use hint files for fast startup if present |
| `recovery.fail.on.corrupt.hint` | Boolean | false | No | None | Abort startup on corrupt hint file vs. fall back to full scan |

---

## Pipeline overview

```text
Bitcask Main
      │
      ▼
ConfigurationLoader           (Facade / Orchestrator)
      │
      ▼
ConfigurationReader           (reads raw text)
      │
      ▼
ConfigurationParser           (raw text → key/value + line numbers)
      │
      ▼
ConverterFactory               (string → typed value, per ConfigOption)
      │
      ▼
ConfigurationValidator         (required / range / cross-property)
      │
      ▼
ConfigurationBuilder           (routes keys → grouped sub-configs)
      │
      ▼
BitcaskConfig                  (immutable root config)
      │
      ▼
Bitcask Engine
```

Example config file this pipeline would consume:

```properties
# bitcask.conf
storage.path=/var/lib/bitcask/data
segment.size=128MB
sync.strategy=INTERVAL
sync.interval=2s
merge.threshold=0.4
```

---

## Package structure

```text
config/
├── metadata/     ConfigOption<T>, ConfigOptionBuilder, ConfigRegistry, Size
├── converter/     Converter<T> + implementations, ConverterFactory
├── validation/    Validator<T>, ConfigurationValidator, ValidationFailure, CrossPropertyRule
├── model/         StorageConfig, SyncConfig, MergeConfig, LimitsConfig, RecoveryConfig,
│                  ReservedConfig, BitcaskConfig
├── builder/       ConfigurationBuilder
├── reader/        ConfigurationReader
├── parser/        ConfigurationParser, RawProperty
├── loader/        ConfigurationLoader
└── exception/     BitcaskConfigException
```

---

## `config.metadata`

### `ConfigOption<T>`

**What it does:** Describes one configuration property completely — its key, its Java type, its default, whether it's required, its documentation, and how to validate it. This is the single source of truth every other stage (converter, validator, builder) reads from instead of hardcoding property names as raw strings.

```java
package config.metadata;

/**
 * Immutable metadata describing a single Bitcask configuration property.
 * Fuses the property's schema (key, type, default, description, validation)
 * into one typed, immutable object — the single source of truth referenced
 * by the converter, validator, and builder stages.
 *
 * @param <T> the Java type this option's value is converted to
 */
public final class ConfigOption<T> {

    private final String key;
    private final Class<T> type;
    private final T defaultValue;
    private final boolean required;
    private final String description;
    private final Validator<T> validator;

    ConfigOption(String key, Class<T> type, T defaultValue, boolean required,
                 String description, Validator<T> validator);

    /** @return the dotted property key, e.g. {@code "segment.size"} */
    public String key();

    /** @return the Java type this option's raw value converts to */
    public Class<T> type();

    /** @return the default value, or {@code null} if none and not required */
    public T defaultValue();

    /** @return true if this property must be present with no fallback default */
    public boolean isRequired();

    /** @return human-readable documentation for this property */
    public String description();

    /** @return the validator to run against a converted value, or {@code null} */
    public Validator<T> validator();
}
```

**Example:** declaring the `segment.size` property once, then referencing that same object everywhere instead of the string `"segment.size"`:

```java
public static final ConfigOption<Size> SEGMENT_SIZE =
        ConfigOptionBuilder.key("segment.size")
                .sizeType()
                .defaultValue(Size.parse("128MB"))
                .withDescription("Threshold at which the active segment is sealed and rotated.")
                .build();

// elsewhere:
Size configuredSize = SEGMENT_SIZE.defaultValue(); // no casting, no string lookup
```

### `ConfigOptionBuilder`

**What it does:** Builds a `ConfigOption<T>` step by step, narrowing the generic type at each stage so the compiler — not a runtime check — catches type mistakes like calling `.defaultValue("128MB")` on an `intType()` option.

```java
package config.metadata;

/**
 * Type-narrowing staged builder for {@link ConfigOption}. Each {@code xxxType()}
 * call returns a builder parameterized on that type, so subsequent calls like
 * {@code defaultValue(T)} are checked by the compiler against the declared type.
 */
public final class ConfigOptionBuilder {

    private final String key;

    private ConfigOptionBuilder(String key);

    /** Entry point. @return an untyped builder stage for further narrowing */
    public static ConfigOptionBuilder key(String key);

    public TypedStage<Integer> intType();
    public TypedStage<Long> longType();
    public TypedStage<Double> doubleType();
    public TypedStage<Boolean> booleanType();
    public TypedStage<String> stringType();
    public TypedStage<java.nio.file.Path> pathType();
    public TypedStage<Size> sizeType();
    public TypedStage<java.time.Duration> durationType();
    public <E extends Enum<E>> TypedStage<E> enumType(Class<E> enumClass);

    /**
     * Type-narrowed builder stage. Holds the type token fixed from the
     * preceding {@code xxxType()} call.
     *
     * @param <T> the narrowed type for this option
     */
    public static final class TypedStage<T> {

        private final String key;
        private final Class<T> type;
        private T defaultValue;
        private boolean required;
        private String description;
        private Validator<T> validator;

        TypedStage(String key, Class<T> type);

        public TypedStage<T> defaultValue(T value);
        public TypedStage<T> required();
        public TypedStage<T> withDescription(String description);
        public TypedStage<T> withValidator(Validator<T> validator);

        /** @return the fully constructed, immutable {@link ConfigOption} */
        public ConfigOption<T> build();
    }
}
```

**Example:** building the `sync.strategy` enum option and the `merge.threshold` double option — note `enumType` narrows to `SyncConfig.Strategy`, so `.defaultValue(SyncConfig.Strategy.NONE)` is the only thing that compiles:

```java
public static final ConfigOption<SyncConfig.Strategy> SYNC_STRATEGY =
        ConfigOptionBuilder.key("sync.strategy")
                .enumType(SyncConfig.Strategy.class)
                .defaultValue(SyncConfig.Strategy.NONE)
                .build();

public static final ConfigOption<Double> MERGE_THRESHOLD =
        ConfigOptionBuilder.key("merge.threshold")
                .doubleType()
                .defaultValue(0.4)
                .build();
```

### `ConfigRegistry`

**What it does:** Holds every declared `ConfigOption` in one lookup table, keyed by property name, so any stage can ask "what's the metadata for `segment.size`?" without needing a reference to the specific `BitcaskConfigOptions` field. Populated automatically via reflection the first time it's touched.

```java
package config.metadata;

/**
 * Central registry of every declared {@link ConfigOption}, keyed by property
 * name. Populated once via reflection over {@code BitcaskConfigOptions}'
 * {@code public static final} fields, using a lazy Bill Pugh holder for
 * thread-safe, on-demand initialization.
 */
public final class ConfigRegistry {

    private ConfigRegistry();

    /** @return the {@link ConfigOption} registered under {@code key}, or {@code null} */
    public static ConfigOption<?> find(String key);

    /** @return true if a {@link ConfigOption} is registered under {@code key} */
    public static boolean contains(String key);

    /** @return every registered option, for documentation generation */
    public static java.util.Collection<ConfigOption<?>> allOptions();

    /** Lazily initialized holder — populated via reflection on first access. */
    private static final class Holder {
        private static final java.util.Map<String, ConfigOption<?>> OPTIONS_BY_KEY;
        static { /* reflective scan, no impl yet */ OPTIONS_BY_KEY = null; }
    }
}
```

**Example:** the parser encounters an unrecognized key in the config file and needs to check if it's even a real property before trying to convert it:

```java
if (!ConfigRegistry.contains("segment.sizee")) {  // typo'd key
    throw BitcaskConfigException.unknownProperty("segment.sizee");
}
```

### `Size`

**What it does:** Represents a human-readable size like `"128MB"` as a plain byte count internally, so every downstream consumer works with `long bytes` rather than re-parsing strings.

```java
package config.metadata;

/**
 * Represents a parsed size value (e.g. {@code "128MB"}, {@code "4KB"}) as a
 * byte count. Value object.
 */
public final class Size {

    private final long bytes;

    private Size(long bytes);

    /** @return a {@link Size} parsed from a string like {@code "128MB"} */
    public static Size parse(String raw);

    /** @return the size in raw bytes */
    public long bytes();
}
```

**Example:**

```java
Size segmentSize = Size.parse("128MB");
long bytes = segmentSize.bytes(); // 134217728
```

---

## `config.converter`

### `Converter<T>`

**What it does:** Defines the one-method contract every type converter implements — take a raw string, return a typed value. This is the Strategy interface: each type gets its own interchangeable implementation.

```java
package config.converter;

/**
 * Strategy interface for converting a raw string property value into a
 * strongly typed value.
 *
 * @param <T> the target type produced by this converter
 */
public interface Converter<T> {

    /**
     * @param raw the unconverted string value from the parsed source
     * @return the converted, typed value
     * @throws config.exception.BitcaskConfigException if {@code raw} cannot be converted
     */
    T convert(String raw);
}
```

**Example:** any converter can be used polymorphically through this interface:

```java
Converter<Boolean> converter = new BooleanConverter();
boolean syncOnWrite = converter.convert("true");
```

### `BooleanConverter`

**What it does:** Converts the literal strings `"true"`/`"false"` (case-insensitive) into a `Boolean`.

```java
package config.converter;

/** Converts {@code "true"} / {@code "false"} into {@link Boolean}. */
public final class BooleanConverter implements Converter<Boolean> {
    @Override
    public Boolean convert(String raw);
}
```

**Example:**

```java
new BooleanConverter().convert("TRUE");  // -> true
```

### `IntegerConverter`

**What it does:** Converts a decimal string into an `Integer`.

```java
package config.converter;

/** Converts decimal integer strings into {@link Integer}. */
public final class IntegerConverter implements Converter<Integer> {
    @Override
    public Integer convert(String raw);
}
```

**Example:**

```java
new IntegerConverter().convert("1000");  // -> 1000
```

### `LongConverter`

**What it does:** Same as `IntegerConverter`, but for values that may exceed `int` range.

```java
package config.converter;

/** Converts decimal integer strings into {@link Long}. */
public final class LongConverter implements Converter<Long> {
    @Override
    public Long convert(String raw);
}
```

**Example:**

```java
new LongConverter().convert("9999999999");  // -> 9999999999L
```

### `DoubleConverter`

**What it does:** Converts a decimal string into a `Double` — used for `merge.threshold` (0.0–1.0).

```java
package config.converter;

/** Converts decimal strings into {@link Double}. */
public final class DoubleConverter implements Converter<Double> {
    @Override
    public Double convert(String raw);
}
```

**Example:**

```java
new DoubleConverter().convert("0.4");  // -> 0.4
```

### `StringConverter`

**What it does:** Passes a string through with minimal validation — the "identity" converter, used for properties like `file.permissions` that stay as strings.

```java
package config.converter;

/** Passes strings through unchanged, with only null/empty checks. */
public final class StringConverter implements Converter<String> {
    @Override
    public String convert(String raw);
}
```

**Example:**

```java
new StringConverter().convert("rw-r-----");  // -> "rw-r-----"
```

### `PathConverter`

**What it does:** Converts a filesystem path string into a `java.nio.file.Path`, used for `storage.path`.

```java
package config.converter;

/** Converts filesystem path strings into {@link java.nio.file.Path}. */
public final class PathConverter implements Converter<java.nio.file.Path> {
    @Override
    public java.nio.file.Path convert(String raw);
}
```

**Example:**

```java
Path dataDir = new PathConverter().convert("/var/lib/bitcask/data");
```

### `SizeConverter`

**What it does:** Parses human-readable sizes like `"128MB"` into a `Size`, splitting the numeric magnitude from the unit suffix and doing the byte-multiplication.

```java
package config.converter;

import config.metadata.Size;

/**
 * Converts human-readable size strings (e.g. {@code "128MB"}, {@code "4KB"},
 * {@code "2GB"}) into a {@link Size} value in bytes.
 */
public final class SizeConverter implements Converter<Size> {

    @Override
    public Size convert(String raw);

    /** @return the numeric magnitude and unit suffix parsed out of {@code raw} */
    private ParsedUnit parseUnit(String raw);

    private static final class ParsedUnit {
        final long magnitude;
        final String unit;
        ParsedUnit(long magnitude, String unit);
    }
}
```

**Example:**

```java
Size size = new SizeConverter().convert("128MB");
size.bytes();  // -> 134217728
```

### `DurationConverter`

**What it does:** Parses human-readable durations like `"5m"` or `"30s"` into a `java.time.Duration`.

```java
package config.converter;

/**
 * Converts human-readable duration strings (e.g. {@code "30s"}, {@code "5m"},
 * {@code "1h"}) into {@link java.time.Duration}.
 */
public final class DurationConverter implements Converter<java.time.Duration> {
    @Override
    public java.time.Duration convert(String raw);
}
```

**Example:**

```java
Duration interval = new DurationConverter().convert("5m");
interval.toSeconds();  // -> 300
```

### `EnumConverter<E>`

**What it does:** Converts a textual value into the matching constant of any enum type, case-insensitively — reused for `sync.strategy` (`SyncConfig.Strategy`) and any future enum property.

```java
package config.converter;

/**
 * Converts a textual value into the matching constant of enum type {@code E},
 * case-insensitively.
 *
 * @param <E> the enum type to convert into
 */
public final class EnumConverter<E extends Enum<E>> implements Converter<E> {

    private final Class<E> enumClass;

    public EnumConverter(Class<E> enumClass);

    @Override
    public E convert(String raw);
}
```

**Example:**

```java
Converter<SyncConfig.Strategy> converter = new EnumConverter<>(SyncConfig.Strategy.class);
SyncConfig.Strategy strategy = converter.convert("interval");  // -> SyncConfig.Strategy.INTERVAL
```

### `ConverterFactory`

**What it does:** Looks up the right `Converter` for a given `ConfigOption`'s declared type, so the rest of the pipeline never needs an `if/else` chain over types — it just calls `factory.convert(option, rawValue)`.

```java
package config.converter;

import config.metadata.ConfigOption;

/**
 * Factory (+ registry) mapping a target type to its {@link Converter},
 * and the single entry point stages further upstream use to convert a
 * raw value according to its {@link ConfigOption}'s declared type.
 */
public final class ConverterFactory {

    private final java.util.Map<Class<?>, Converter<?>> convertersByType;

    public ConverterFactory();

    /** @return the {@link Converter} registered for {@code type} */
    @SuppressWarnings("unchecked")
    public <T> Converter<T> getConverter(Class<T> type);

    /** Converts {@code raw} according to {@code option}'s declared type. */
    public <T> T convert(ConfigOption<T> option, String raw);

    /** Registers or replaces the converter used for {@code type}. */
    public <T> void register(Class<T> type, Converter<T> converter);
}
```

**Example:**

```java
ConverterFactory factory = new ConverterFactory();
Size segmentSize = factory.convert(BitcaskConfigOptions.SEGMENT_SIZE, "128MB");
```

---

## `config.validation`

### `Validator<T>`

**What it does:** Defines the contract for checking a single converted value against a rule — e.g. "must be greater than zero." Each `ConfigOption` can carry one of these.

```java
package config.validation;

/**
 * Specification-pattern rule validating a single converted property value.
 *
 * @param <T> the type of value this validator checks
 */
public interface Validator<T> {

    /**
     * @param propertyKey the property key being validated, for error messages
     * @param value the converted value to validate
     * @throws config.exception.BitcaskConfigException if {@code value} is invalid
     */
    void validate(String propertyKey, T value);
}
```

**Example:** a validator ensuring `merge.threshold` stays within 0.0–1.0:

```java
Validator<Double> rangeCheck = (key, value) -> {
    if (value < 0.0 || value > 1.0) {
        throw BitcaskConfigException.invalidValue(key, String.valueOf(value), "must be between 0 and 1");
    }
};
```

### `ConfigurationValidator`

**What it does:** Runs every applicable validation layer — required fields present, per-option validators, cross-property rules — across the whole converted config, and collects every failure instead of stopping at the first, so error reports can be complete.

```java
package config.validation;

import config.metadata.ConfigOption;
import config.metadata.ConfigRegistry;

/**
 * Orchestrates validation across every registered {@link ConfigOption}:
 * required-field checks, per-option validators, and cross-property rules.
 * Collects all failures rather than stopping at the first, to support
 * useful, complete diagnostics.
 */
public final class ConfigurationValidator {

    private final java.util.List<CrossPropertyRule> crossPropertyRules;

    public ConfigurationValidator();

    /**
     * Runs every validation layer against the converted property map.
     *
     * @param convertedValues keys mapped to their already-converted, typed values
     * @return the list of validation failures found, empty if all valid
     */
    public java.util.List<ValidationFailure> validateAll(
            java.util.Map<String, Object> convertedValues);

    private java.util.List<ValidationFailure> validateRequired(
            java.util.Map<String, Object> convertedValues);

    private java.util.List<ValidationFailure> validatePerOption(
            java.util.Map<String, Object> convertedValues);

    private java.util.List<ValidationFailure> validateCrossProperty(
            java.util.Map<String, Object> convertedValues);

    /** Registers a rule spanning more than one property, e.g. sync.interval required-if. */
    public void addCrossPropertyRule(CrossPropertyRule rule);
}
```

**Example:**

```java
ConfigurationValidator validator = new ConfigurationValidator();
List<ValidationFailure> failures = validator.validateAll(convertedValues);
if (!failures.isEmpty()) {
    failures.forEach(f -> System.err.println(f.propertyKey() + ": " + f.reason()));
}
```

### `ValidationFailure`

**What it does:** A tiny value object pairing a property key with why it failed validation — the unit of reporting for `ConfigurationValidator`.

```java
package config.validation;

/** A single validation failure: which property, and why. */
public final class ValidationFailure {

    private final String propertyKey;
    private final String reason;

    public ValidationFailure(String propertyKey, String reason);

    public String propertyKey();
    public String reason();
}
```

**Example:**

```java
new ValidationFailure("merge.threshold", "must be between 0 and 1");
```

### `CrossPropertyRule`

**What it does:** Represents a validation rule that spans more than one property — like "`sync.interval` is required if `sync.strategy` is `INTERVAL`" — which no single `ConfigOption`'s own validator can express alone.

```java
package config.validation;

/** A validation rule spanning more than one property. */
public interface CrossPropertyRule {

    /**
     * @param convertedValues the full converted property map
     * @return a failure if the rule is violated, or {@code null} if satisfied
     */
    ValidationFailure validate(java.util.Map<String, Object> convertedValues);
}
```

**Example:**

```java
CrossPropertyRule syncIntervalRequired = values -> {
    Object strategy = values.get("sync.strategy");
    Object interval = values.get("sync.interval");
    if (SyncConfig.Strategy.INTERVAL.equals(strategy) && interval == null) {
        return new ValidationFailure("sync.interval", "required when sync.strategy=INTERVAL");
    }
    return null;
};
```

---

## `config.model`

### `StorageConfig`

**What it does:** Groups the storage-layer settings (`storage.path`, `segment.size`, `file.permissions`) into one immutable object that `DataFile`/`BitcaskStore` will actually consult.

```java
package config.model;

/** Immutable storage-layer settings. */
public final class StorageConfig {

    private final java.nio.file.Path dataDirectory;
    private final config.metadata.Size segmentSize;
    private final String filePermissions;

    private StorageConfig(Builder builder);

    public java.nio.file.Path dataDirectory();
    public config.metadata.Size segmentSize();
    public String filePermissions();

    public static final class Builder {
        private java.nio.file.Path dataDirectory;
        private config.metadata.Size segmentSize;
        private String filePermissions;

        public Builder dataDirectory(java.nio.file.Path dataDirectory);
        public Builder segmentSize(config.metadata.Size segmentSize);
        public Builder filePermissions(String filePermissions);
        public StorageConfig build();
    }
}
```

**Example:**

```java
StorageConfig storage = new StorageConfig.Builder()
        .dataDirectory(Path.of("/var/lib/bitcask/data"))
        .segmentSize(Size.parse("128MB"))
        .filePermissions("rw-r-----")
        .build();
```

### `SyncConfig`

**What it does:** Groups durability settings — whether and how often to fsync.

```java
package config.model;

/** Immutable durability/sync settings. */
public final class SyncConfig {

    /** Sync strategy: fsync never, every write, or on an interval. */
    public enum Strategy { NONE, EVERY_WRITE, INTERVAL }

    private final Strategy strategy;
    private final java.time.Duration interval;

    private SyncConfig(Builder builder);

    public Strategy strategy();
    public java.time.Duration interval();

    public static final class Builder {
        private Strategy strategy;
        private java.time.Duration interval;

        public Builder strategy(Strategy strategy);
        public Builder interval(java.time.Duration interval);
        public SyncConfig build();
    }
}
```

**Example:**

```java
SyncConfig sync = new SyncConfig.Builder()
        .strategy(SyncConfig.Strategy.INTERVAL)
        .interval(Duration.ofSeconds(2))
        .build();
```

### `MergeConfig`

**What it does:** Groups compaction settings — whether auto-merge runs, the dead-byte threshold that triggers eligibility, and the scheduling intervals `MergeManager` will read.

```java
package config.model;

/** Immutable merge/compaction settings. */
public final class MergeConfig {

    private final boolean autoEnabled;
    private final double threshold;
    private final java.time.Duration minFileAge;
    private final java.time.Duration checkInterval;

    private MergeConfig(Builder builder);

    public boolean autoEnabled();
    public double threshold();
    public java.time.Duration minFileAge();
    public java.time.Duration checkInterval();

    public static final class Builder {
        private boolean autoEnabled;
        private double threshold;
        private java.time.Duration minFileAge;
        private java.time.Duration checkInterval;

        public Builder autoEnabled(boolean autoEnabled);
        public Builder threshold(double threshold);
        public Builder minFileAge(java.time.Duration minFileAge);
        public Builder checkInterval(java.time.Duration checkInterval);
        public MergeConfig build();
    }
}
```

**Example:**

```java
MergeConfig merge = new MergeConfig.Builder()
        .autoEnabled(true)
        .threshold(0.4)
        .minFileAge(Duration.ofMinutes(10))
        .checkInterval(Duration.ofMinutes(5))
        .build();
```

### `LimitsConfig`

**What it does:** Groups the key/value size ceilings that `LogRecord`'s existing `keyTooLarge()`/`valueTooLarge()` checks compare against.

```java
package config.model;

/** Immutable key/value size limits. */
public final class LimitsConfig {

    private final config.metadata.Size keyMaxSize;
    private final config.metadata.Size valueMaxSize;

    private LimitsConfig(Builder builder);

    public config.metadata.Size keyMaxSize();
    public config.metadata.Size valueMaxSize();

    public static final class Builder {
        private config.metadata.Size keyMaxSize;
        private config.metadata.Size valueMaxSize;

        public Builder keyMaxSize(config.metadata.Size keyMaxSize);
        public Builder valueMaxSize(config.metadata.Size valueMaxSize);
        public LimitsConfig build();
    }
}
```

**Example:**

```java
LimitsConfig limits = new LimitsConfig.Builder()
        .keyMaxSize(Size.parse("1KB"))
        .valueMaxSize(Size.parse("1MB"))
        .build();
```

### `RecoveryConfig`

**What it does:** Groups startup behavior — whether to trust hint files and what to do if one is corrupt.

```java
package config.model;

/** Immutable startup/recovery settings. */
public final class RecoveryConfig {

    private final boolean useHintFile;
    private final boolean failOnCorruptHint;

    private RecoveryConfig(Builder builder);

    public boolean useHintFile();
    public boolean failOnCorruptHint();

    public static final class Builder {
        private boolean useHintFile;
        private boolean failOnCorruptHint;

        public Builder useHintFile(boolean useHintFile);
        public Builder failOnCorruptHint(boolean failOnCorruptHint);
        public RecoveryConfig build();
    }
}
```

**Example:**

```java
RecoveryConfig recovery = new RecoveryConfig.Builder()
        .useHintFile(true)
        .failOnCorruptHint(false)
        .build();
```

### `ReservedConfig`

**What it does:** Isolates the three properties (`compression.enabled`, `cache.read.size`, `buffer.write.size`) that are declared but not yet wired into any real subsystem — keeping them here makes it obvious at a glance that setting them currently has no effect.

```java
package config.model;

/**
 * Reserved settings not yet wired into the storage engine
 * (compression, read cache, write buffer). Kept isolated so it's obvious
 * these fields are inert until their owning subsystems exist.
 */
public final class ReservedConfig {

    private final boolean compressionEnabled;
    private final config.metadata.Size cacheReadSize;
    private final config.metadata.Size bufferWriteSize;

    private ReservedConfig(Builder builder);

    public boolean compressionEnabled();
    public config.metadata.Size cacheReadSize();
    public config.metadata.Size bufferWriteSize();

    public static final class Builder {
        private boolean compressionEnabled;
        private config.metadata.Size cacheReadSize;
        private config.metadata.Size bufferWriteSize;

        public Builder compressionEnabled(boolean compressionEnabled);
        public Builder cacheReadSize(config.metadata.Size cacheReadSize);
        public Builder bufferWriteSize(config.metadata.Size bufferWriteSize);
        public ReservedConfig build();
    }
}
```

**Example:**

```java
ReservedConfig reserved = new ReservedConfig.Builder()
        .compressionEnabled(false)
        .cacheReadSize(Size.parse("0"))
        .bufferWriteSize(Size.parse("0"))
        .build();
```

### `BitcaskConfig`

**What it does:** The final, top-level immutable config object the whole pipeline produces — one instance holding all six grouped sub-configs, handed to `BitcaskStore` at startup.

```java
package config.model;

/**
 * Immutable root configuration object for the Bitcask engine. Holds one
 * instance of each grouped sub-config. Constructed only via
 * {@link config.builder.ConfigurationBuilder}.
 */
public final class BitcaskConfig {

    private final StorageConfig storage;
    private final SyncConfig sync;
    private final MergeConfig merge;
    private final LimitsConfig limits;
    private final RecoveryConfig recovery;
    private final ReservedConfig reserved;

    public BitcaskConfig(StorageConfig storage, SyncConfig sync, MergeConfig merge,
                          LimitsConfig limits, RecoveryConfig recovery, ReservedConfig reserved);

    public StorageConfig storage();
    public SyncConfig sync();
    public MergeConfig merge();
    public LimitsConfig limits();
    public RecoveryConfig recovery();
    public ReservedConfig reserved();
}
```

**Example:**

```java
BitcaskConfig config = loader.load();
Path dataDir = config.storage().dataDirectory();
boolean shouldMerge = config.merge().autoEnabled();
```

---

## `config.builder`

### `ConfigurationBuilder`

**What it does:** Takes the flat map of converted values (e.g. `"segment.size" -> Size(128MB)`) and routes each entry into the right grouped sub-config builder by its key prefix, then assembles the final `BitcaskConfig`. This is the bridge between the flat property model and the grouped domain model.

```java
package config.builder;

import config.model.*;

/**
 * Assembles a {@link BitcaskConfig} from a flat map of converted, validated
 * property values, routing each key to the correct grouped sub-config
 * builder by its dotted-key prefix.
 */
public final class ConfigurationBuilder {

    private final StorageConfig.Builder storageBuilder;
    private final SyncConfig.Builder syncBuilder;
    private final MergeConfig.Builder mergeBuilder;
    private final LimitsConfig.Builder limitsBuilder;
    private final RecoveryConfig.Builder recoveryBuilder;
    private final ReservedConfig.Builder reservedBuilder;

    public ConfigurationBuilder();

    /**
     * Populates every sub-builder from the converted values, routing each
     * key by prefix (e.g. {@code segment.*} → storage, {@code merge.*} → merge).
     *
     * @param convertedValues keys mapped to their converted, typed values
     * @return this builder, for chaining into {@link #build()}
     */
    public ConfigurationBuilder fromConvertedValues(java.util.Map<String, Object> convertedValues);

    /** @return the assembled, immutable {@link BitcaskConfig} */
    public BitcaskConfig build();

    private void routeStorageKey(String key, Object value);
    private void routeSyncKey(String key, Object value);
    private void routeMergeKey(String key, Object value);
    private void routeLimitsKey(String key, Object value);
    private void routeRecoveryKey(String key, Object value);
    private void routeReservedKey(String key, Object value);
}
```

**Example:**

```java
Map<String, Object> converted = Map.of(
        "storage.path", Path.of("/var/lib/bitcask/data"),
        "segment.size", Size.parse("128MB"));

BitcaskConfig config = new ConfigurationBuilder()
        .fromConvertedValues(converted)
        .build();
```

---

## `config.reader`

### `ConfigurationReader`

**What it does:** Reads the raw text of the config file (or another future source) into lines. Knows nothing about keys, values, or types — purely I/O.

```java
package config.reader;

/**
 * Reads raw configuration text from a source (file, stream, future:
 * environment/network). Owns no parsing or type knowledge.
 */
public final class ConfigurationReader {

    private final java.nio.file.Path sourcePath;
    private final java.nio.charset.Charset charset;

    public ConfigurationReader(java.nio.file.Path sourcePath, java.nio.charset.Charset charset);

    /** @return the raw configuration text, one string per line */
    public java.util.List<String> read();

    /** @return true if the configured source exists and is readable */
    public boolean exists();
}
```

**Example:**

```java
ConfigurationReader reader = new ConfigurationReader(Path.of("bitcask.conf"), StandardCharsets.UTF_8);
if (reader.exists()) {
    List<String> lines = reader.read();
}
```

---

## `config.parser`

### `RawProperty`

**What it does:** A single unconverted key/value pair, tagged with the line number it came from — the raw material diagnostics will point to when something's wrong.

```java
package config.parser;

/**
 * A single parsed key/value entry with its originating line number,
 * preserved for diagnostics.
 */
public final class RawProperty {

    private final String key;
    private final String rawValue;
    private final int lineNumber;

    public RawProperty(String key, String rawValue, int lineNumber);

    public String key();
    public String rawValue();
    public int lineNumber();
}
```

**Example:**

```java
new RawProperty("segment.size", "128MB", 4);  // line 4 of the config file
```

### `ConfigurationParser`

**What it does:** Turns the reader's raw lines into a map of `RawProperty` entries — skipping comments and blank lines, splitting on the delimiter, and remembering each entry's line number.

```java
package config.parser;

/**
 * Converts raw configuration lines into a map of {@link RawProperty}
 * entries, skipping comments and blank lines, tracking line numbers.
 */
public final class ConfigurationParser {

    private final char delimiter;
    private final char commentPrefix;

    public ConfigurationParser(char delimiter, char commentPrefix);

    /**
     * @param lines raw text lines from {@link config.reader.ConfigurationReader}
     * @return key-to-{@link RawProperty} map, comments and blanks excluded
     */
    public java.util.Map<String, RawProperty> parse(java.util.List<String> lines);

    /** Parses a single non-comment, non-blank line into a {@link RawProperty}. */
    private RawProperty parseLine(String line, int lineNumber);

    /** @throws config.exception.BitcaskConfigException if the line is malformed */
    private void validateSyntax(String line, int lineNumber);
}
```

**Example:** given the lines

```text
# comment, ignored
segment.size=128MB
sync.strategy=INTERVAL
```

`parse()` produces:

```java
{
  "segment.size"  -> RawProperty("segment.size", "128MB", 2),
  "sync.strategy" -> RawProperty("sync.strategy", "INTERVAL", 3)
}
```

---

## `config.loader`

### `ConfigurationLoader`

**What it does:** The Facade tying every stage together — read, parse, convert, validate, build — into one `load()` call, so `BitcaskStore`'s startup code doesn't need to know the pipeline exists.

```java
package config.loader;

import config.model.BitcaskConfig;

/**
 * Orchestrates the full configuration loading pipeline: read → parse →
 * convert → validate → build. Facade over the individual stage classes.
 */
public final class ConfigurationLoader {

    private final config.reader.ConfigurationReader reader;
    private final config.parser.ConfigurationParser parser;
    private final config.converter.ConverterFactory converterFactory;
    private final config.validation.ConfigurationValidator validator;
    private final config.builder.ConfigurationBuilder builder;

    public ConfigurationLoader(config.reader.ConfigurationReader reader,
                                config.parser.ConfigurationParser parser,
                                config.converter.ConverterFactory converterFactory,
                                config.validation.ConfigurationValidator validator,
                                config.builder.ConfigurationBuilder builder);

    /**
     * Runs the complete pipeline and returns the finished, immutable config.
     *
     * @throws config.exception.BitcaskConfigException if any stage fails
     */
    public BitcaskConfig load();

    /** @return a {@link BitcaskConfig} built entirely from declared defaults */
    public BitcaskConfig loadDefaults();
}
```

**Example:**

```java
ConfigurationLoader loader = new ConfigurationLoader(
        new ConfigurationReader(Path.of("bitcask.conf"), StandardCharsets.UTF_8),
        new ConfigurationParser('=', '#'),
        new ConverterFactory(),
        new ConfigurationValidator(),
        new ConfigurationBuilder());

BitcaskConfig config = loader.load();
```

---

## `config.exception`

### `BitcaskConfigException`

**What it does:** The single exception type for every configuration failure, with named static factories so error sites read clearly (`missingRequired(...)` instead of a generic constructor call) — same style as the existing `BitcaskException` used by `LogRecord`/`DataFile`.

```java
package config.exception;

/**
 * Root exception for configuration loading failures. Static factories
 * mirror {@code BitcaskException}'s pattern, producing precise,
 * diagnosable error messages per failure kind.
 */
public final class BitcaskConfigException extends RuntimeException {

    private BitcaskConfigException(String message);

    public static BitcaskConfigException missingRequired(String key);

    public static BitcaskConfigException invalidValue(String key, String raw, String reason);

    public static BitcaskConfigException parseError(String sourceName, int lineNumber, String reason);

    public static BitcaskConfigException crossPropertyViolation(String description);

    public static BitcaskConfigException unknownProperty(String key);
}
```

**Example:**

```java
if (rawValue == null) {
    throw BitcaskConfigException.missingRequired("storage.path");
}

throw BitcaskConfigException.parseError("bitcask.conf", 7, "missing '=' delimiter");
```

---

## Open design questions

Things to decide deliberately before or during implementation, not by default:

1. **`ConfigurationBuilder` routing strategy** — switch-on-prefix, a `Map<String, KeyRouter>`, or something else? Not yet specified in the routing method bodies above.
2. **File format** — flat dotted-key `key=value` (matches the parser design here) vs. true nested YAML. Currently designed for the former; revisit if YAML nesting is actually required.
3. **`ConfigOptionBuilder.enumType(Class<E>)` generics** — expect friction getting `E extends Enum<E>` to flow cleanly through the staged builder. Budget extra time here.
4. **`ConverterFactory.getConverter`'s unchecked cast** — the one deliberate escape hatch in the design; keep it isolated to this one method.
5. **`config.diagnostics`** — intentionally not yet designed. Revisit after `ConfigurationValidator` surfaces real failure cases to design good error messages against.
6. **Reflective registry population** — `ConfigRegistry.Holder`'s static initializer needs the actual reflective scan implemented; depends on `BitcaskConfigOptions` (the class declaring all 16 options as `public static final` fields) existing first.
