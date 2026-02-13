# HeadlessMc-LWJGL

This module is responsible for instrumenting the LWJGL library
and thus making the Minecraft client "headless".
It consists of two parts: A transformer that transforms class
files and the so called RedirectionAPI.The transformer is usually
called from HeadlessMc's instrumentation,
to transform LWJGL before running the game.
But it can also run at runtime as a Java agent
or as a LaunchWrapper Tweaker.
If you want to do that you also need to add the system property
`-Djoml.nounsafe=true` to your game, and, if you are
running on fabric, the path to the headlessmc-lwjgl
agent jar to the system property `fabric.systemLibraries`.

The transformer will transform every `org.lwjgl`class in the following way:
Every method body will be replaced with a call to the RedirectionAPI:

```java
public <type> method(<arg>... args) {
    return (<type>) RedirectionApi.invoke(this, "<owner>;method(<arg>)<type>", <type>.class, args);
}
```

The RedirectionApi can return a default value for all
datatypes except abstract classes (interfaces will be implemented
using `java.lang.reflect.Proxy`),
we can also redirect a call manually like this:

```java
RedirectionApi.getRedirectionManager().redirect("<owner>;method(<arg.type>)<type>", <Redirection>);
```

These custom redirections are needed in some
cases to ensure that the game does not crash.
E.g. for all methods returning Buffers,
as those classes cannot be instantiated easily.
All redirections can be found in the
[redirections package](src/main/java/io/github/headlesshq/headlessmc/lwjgl/redirections).
An example:

```java
manager.redirect("Lorg/lwjgl/BufferUtils;createFloatBuffer(I)"Ljava/nio/FloatBuffer;",
                         (obj, desc, type, args) -> FloatBuffer.wrap(
                             new float[(int) args[0]]));
```

## STB TrueType Font Support

Some mods (e.g. Meteor Client) use LWJGL's STB TrueType bindings
(`org.lwjgl.stb.STBTruetype`) to load and inspect `.ttf` font files.
Since the transformer replaces all `org.lwjgl` method bodies,
the native STB font parsing functions are unavailable in headless mode.

The [STBTrueTypeRedirections](src/main/java/io/github/headlesshq/headlessmc/lwjgl/redirections/stb/STBTrueTypeRedirections.java)
class handles this by implementing a pure-Java TrueType name table parser:

- **`stbtt_InitFont`**: Parses the TrueType/OpenType `name` table from
  the raw font `ByteBuffer` and caches all name records
  (family name, style, etc.) keyed by the `STBTTFontinfo` instance.
  Returns `true` to indicate success.
- **`stbtt_GetFontNameString`**: Looks up cached name records by
  platformID, encodingID, languageID, and nameID, returning the
  raw bytes as a `ByteBuffer` just like the real STB function would.
- **`stbtt_GetNumberOfFonts`**: Returns `1` (single font).
- **`stbtt_ScaleForPixelHeight`**: Returns a plausible scale factor
  based on a typical 2048 unitsPerEm font, avoiding division-by-zero.
- **`stbtt_GetFontVMetrics`**: Fills ascent/descent/lineGap buffers
  with typical values so font metrics calculations don't get all zeros.

## StructBuffer Generic Type Fix

LWJGL's `StructBuffer<T, SELF>` has `get()` methods that return `T`,
but due to Java generic type erasure the bytecode return type is the
base `Struct` class. The default `ObjectRedirection` creates a bare
`Struct` instance, which causes a `ClassCastException` when callers
cast to the expected subclass (e.g. `STBTTPackRange`).

The [StructBufferRedirection](src/main/java/io/github/headlesshq/headlessmc/lwjgl/redirections/StructBufferRedirection.java)
class resolves this by inspecting the generic superclass hierarchy of
the `StructBuffer` instance at runtime to determine the actual element
type, then instantiating the correct subclass. Results are cached for
performance.
