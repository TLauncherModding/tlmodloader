# TLModLoader

TLModLoader is a lightweight modding platform for TLauncher.

It adds a modern mod-loading environment to TLauncher while remaining lightweight and dependency-free from any specific mod ecosystem. It supports Sponge Mixin, Fabric Access Wideners, runtime bytecode transformers (coremods), nested JARs, and automatic dependency resolution.

The goal is to provide a simple but powerful API that allows developers to extend TLauncher without modifying TLauncher itself.

---

# Features

- Sponge Mixin support
- MixinExtras support
- Fabric Access Widener support
- Runtime coremods (ASM transformers)
- Nested JAR (Jar-in-Jar) loading
- Automatic dependency loading
- Wildcard dependency resolution
- Cross-platform installer
- No modifications required to mod JARs
- Public Domain (Unlicense)

---

# Installation

## Step 1

Download the latest TLModLoader release.

Run:

```bash
java -jar tlmodloader.jar
```

---

## Step 2

Select your TLauncher installation.

The installer will automatically locate installations on:

### Windows

```
%APPDATA%\tlauncher
```

### Linux

```
~/.tlauncher
```

### macOS

```
~/Library/Application Support/tlauncher
```

---

## Step 3

Click **Install**.

The installer will:

- Patch `dependencies.json`
- Patch `appConfig.json`
- Download required libraries
- Install TLModLoader
- Configure TLauncher to launch through the wrapper

No manual editing is required.

---

# Installing Mods

Create a folder named:

```
mods
```

in the same directory as the TLauncher executable.

Place mod JARs inside:

```
mods/
    ExampleMod.jar
    MyMod.jar
```

Restart TLauncher.

TLModLoader will automatically discover and load every valid mod.

---

# Mod Structure

A TLModLoader mod is simply a JAR file containing a `tlmod.json`.

Example:

```
ExampleMod.jar
│
├── tlmod.json
├── example.mixins.json
├── example.accesswidener
└── com/
    └── example/
        └── ExampleMod.class
```

---

# tlmod.json

Example:

```json
{
  "id": "examplemod",
  "version": "1.0.0",

  "entrypoints": [
    "com.example.ExampleMod"
  ],

  "mixins": [
    "example.mixins.json"
  ],

  "accessWideners": [
    "example.accesswidener"
  ],

  "coremods": [
    "com.example.ExampleTransformer"
  ],

  "dependencies": [
    "${APP_DIR}/libs/*.jar"
  ]
}
```

All fields are optional except:

- `id`
- `version`

---

# Entrypoints

Entrypoints are invoked after Mixin initialisation.

Example:

```java
public class ExampleMod {

    public static void init() {
        System.out.println("Hello from TLModLoader!");
    }

}
```

The method **must** be:

```java
public static void init()
```

---

# Mixins

Mixin configuration files are registered automatically.

Simply include them in `tlmod.json`.

Example:

```json
"mixins": [
    "example.mixins.json"
]
```

No additional bootstrap code is required.

MixinExtras is initialized automatically.

---

# Access Wideners

TLModLoader supports Fabric Access Wideners.

Example:

```json
"accessWideners": [
    "example.accesswidener"
]
```

Access wideners are applied before Mixins are processed.

---

# Coremods

Coremods allow arbitrary bytecode transformation before Mixins.

Create a class implementing:

```java
public interface ICoremodTransformer {

    byte[] transform(String className, byte[] classBytes);

}
```

Then register it:

```json
"coremods": [
    "com.example.ExampleTransformer"
]
```

Transformers execute before Mixins.

---

# Dependencies

Mods may specify additional classpath dependencies.

Example:

```json
"dependencies": [
    "${HOME_DIR}/libs/example.jar"
]
```

or

```json
"dependencies": [
    "${APP_DIR}/libs/*.jar"
]
```

or

```json
"dependencies": [
    "${APP_DIR}/plugins/**/*.jar"
]
```

Supported macros:

- `${HOME_DIR}`
- `${APP_DIR}`

Supported wildcards:

- `*`
- `*.jar`
- `**`

Nested JARs located inside:

```
META-INF/jars/
```

are also loaded automatically.

---

# Load Order

TLModLoader initializes in the following order:

1. Discover mods
2. Load nested JARs
3. Resolve dependency JARs
4. Extend the runtime classpath
5. Apply Access Wideners
6. Register coremods
7. Initialize Sponge Mixin
8. Initialize MixinExtras
9. Register mixin configurations
10. Invoke mod entrypoints
11. Launch TLauncher

---

# Building Mods

A TLModLoader mod is simply a normal Java library.

Recommended dependencies:

```gradle
implementation("org.spongepowered:mixin:0.15.0")
implementation("com.github.llamalad7:mixinextras:0.4.1")
implementation("net.fabricmc:access-widener:2.1.1")
```

Package your compiled classes together with:

- `tlmod.json`
- Mixins (optional)
- Access Widener (optional)

Build the JAR and place it in the `mods` folder.

---

# Building TLModLoader

Clone:

```bash
git clone git@github.com:TLauncherModding/tlmodloader.git
```

Build:

```bash
./gradlew build
```

The installer JAR will be produced in:

```
build/libs/
```

---

# Licence

This project is released into the Public Domain under **The Unlicense**.

You may use, modify, distribute, sell, sublicense, or incorporate this software into any project without restriction or attribution, although attribution is appreciated.