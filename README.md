# HeadlessMc Offline

It's HeadlessMc, but patched for using offline accounts on servers. For further information about HeadlessMc, see the [upstream repository](https://github.com/headlesshq/headlessmc).

## `-noAuth` Flag

The `-noAuth` flag patches Mojang's `authlib` at launch time so that offline or invalid accounts can join multiplayer servers. It works by replacing `YggdrasilMinecraftSessionService.joinServer()` with a no-op, preventing the client from sending an authentication request to Mojang's session servers.

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

### How It Works

Normally, when a Minecraft client connects to a server, it calls `joinServer()` to authenticate with Mojang's session servers. This fails with a `401` for offline/invalid accounts and prevents the connection. The `-noAuth` flag uses bytecode instrumentation (ASM) to replace the body of that method with a simple `return`, so the client proceeds with the connection without authenticating.

### Important Notes

- **Servers with `online-mode=true`** will still reject the connection, because the server-side `hasJoinedServer` check with Mojang will fail.
- **Servers with `online-mode=false`**, auth plugins, or proxy-based authentication (e.g. BungeeCord/Velocity) will accept the connection.
