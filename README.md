# HeadlessMc Offline

It's HeadlessMc, but patched for using offline accounts on servers and additional fixes for [Meteor client](https://github.com/MeteorDevelopment/meteor-client). For further information about HeadlessMc, see the [upstream repository](https://github.com/headlesshq/headlessmc).

## Why?

We are building a Minecraft Bot Swarm system to be used on servers like 6b6t.
As we need to have a lot of bots running at the same time, we want to rely on HeadlessMc in [our runtime](https://github.com/Torikawa-Organization/SwarmRuntime).
However, HeadlessMc does not support offline accounts, which are essential for our use case.
This project is a fork of HeadlessMc that adds support for offline accounts by patching Mojang's `authlib` at launch time.
In addition, we also add some fixes to get [Meteor client](https://github.com/MeteorDevelopment/meteor-client) working in headless mode.

## ⚠️ DISCLAIMER ABOUT ISSUES ⚠️

We patch HeadlessMc's code. So if you encounter any errors, please first confirm that the error is not caused by our patches. You can do this by trying to do whatever you were doing with the upstream version of HeadlessMc. If the error does **NOT** occur with the upstream version, please do **NOT** bother the maintainers of the upstream repository with the issue! Instead, please report the issue to us by opening a new issue in this repository. We will investigate and fix the issue if it is caused by our patches.

### How It Works

Normally, when a Minecraft client connects to a server, it calls `joinServer()` to authenticate with Mojang's session servers. This fails with a `401` for offline/invalid accounts and prevents the connection. The `-noAuth` flag uses bytecode instrumentation (ASM) to replace the body of that method with a simple `return`, so the client proceeds with the connection without authenticating.

Additionally, Minecraft calls `getKeyPair()` to fetch profile key pairs used for chat signing. For offline/invalid accounts this also results in a `401` error. The patch makes `getKeyPair()` return `null`, which the client handles gracefully by skipping chat signing.

## `-noAuth` Flag

The `-noAuth` flag patches Mojang's `authlib` at launch time so that offline or invalid accounts can join multiplayer servers. It applies two patches:

1. **`YggdrasilMinecraftSessionService.joinServer()`** is replaced with a no-op, preventing the client from sending an authentication request to Mojang's session servers.
2. **`YggdrasilUserApiService.getKeyPair()`** is patched to return `null`, preventing a `401` error when the client tries to retrieve profile key pairs for chat signing.

## Font & STB Fixes for Meteor Client

Mods like [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) use LWJGL's STB TrueType bindings to load `.ttf` font files and STBImage to load textures. Since HeadlessMc's LWJGL transformer replaces all `org.lwjgl` method bodies, these native functions are unavailable in headless mode, causing crashes. We added several fixes:

### STB TrueType Name Table Parser

Instead of returning dummy font names, we implemented a pure-Java TrueType name table parser that reads the real font family and style names from the font data at `stbtt_InitFont` time. This means `stbtt_GetFontNameString` returns the actual font names for any `.ttf` file. Additional redirections for `stbtt_ScaleForPixelHeight` and `stbtt_GetFontVMetrics` provide plausible font metric values.

### StructBuffer Generic Type Fix

LWJGL's `StructBuffer<T>.get()` methods return `T`, but due to Java type erasure the bytecode return type is the base `Struct` class. The default redirection created bare `Struct` instances, causing `ClassCastException` when callers cast to the expected subclass (e.g. `STBTTPackRange`). We added a `StructBufferRedirection` that resolves the actual element type from the generic superclass hierarchy at runtime.

### STBImage Buffer Size Fix

The existing `STBImageRedirection` allocated the result buffer _before_ reading the image, using the initial (zero) values from the width/height IntBuffers. This produced a 0-byte buffer, causing a `BufferUnderflowException` when callers tried to read pixel data. Fixed by reading the image first, then allocating the correctly sized buffer.

For more details, see the [headlessmc-lwjgl README](headlessmc-lwjgl/README.md).

### Important Notes

**Servers with `online-mode=true`** will still reject the connection, because the server-side `hasJoinedServer` check with Mojang will fail.

**Servers with `online-mode=false`**, auth plugins, or proxy-based authentication (e.g. BungeeCord/Velocity) will accept the connection.

### Usage

```
launch <version> -noAuth
```

You can combine it with other flags as usual:

```
launch <version> -noAuth -lwjgl -commands
```

To enable it permanently, set one of the following properties in your config:

| Property                 | Description                                                             |
| ------------------------ | ----------------------------------------------------------------------- |
| `hmc.always.noauth.flag` | Set to `true` to always apply the `-noAuth` patch.                      |
| `hmc.invert.noauth.flag` | Set to `true` to invert the flag (i.e. `-noAuth` _disables_ the patch). |
