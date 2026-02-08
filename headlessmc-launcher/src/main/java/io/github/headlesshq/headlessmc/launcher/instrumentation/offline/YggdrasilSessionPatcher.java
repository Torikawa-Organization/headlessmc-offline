package io.github.headlesshq.headlessmc.launcher.instrumentation.offline;

import static org.objectweb.asm.Opcodes.RETURN;

import java.util.ArrayList;
import java.util.Locale;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import io.github.headlesshq.headlessmc.launcher.instrumentation.AbstractClassTransformer;
import io.github.headlesshq.headlessmc.launcher.instrumentation.Target;
import lombok.CustomLog;

/**
 * Patches {@code com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService}
 * to make the {@code joinServer} method a no-op. This prevents the Minecraft
 * client from sending an authentication request to Mojang's session servers
 * when connecting to a multiplayer server, allowing offline/invalid accounts
 * to attempt to join servers.
 * <p>
 * Note: Whether the server accepts the connection depends on the server's
 * configuration. Servers running in online-mode will still reject the
 * connection because Mojang's {@code hasJoinedServer} check will fail.
 * However, servers with online-mode=false, auth plugins, or proxy-based
 * authentication (e.g. BungeeCord) may accept the connection.
 */
@CustomLog
public class YggdrasilSessionPatcher extends AbstractClassTransformer {
    public YggdrasilSessionPatcher() {
        super("com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService");
    }

    @Override
    public boolean matches(Target target) {
        return target.getPath().toLowerCase(Locale.ENGLISH).contains("authlib");
    }

    @Override
    protected void transform(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if ("joinServer".equals(mn.name)) {
                log.info("Patching joinServer in " + cn.name + " to no-op for offline online server support");
                InsnList insns = new InsnList();
                insns.add(new InsnNode(RETURN));
                mn.instructions = insns;
                mn.tryCatchBlocks = new ArrayList<>();
                mn.localVariables = new ArrayList<>();
                mn.parameters = new ArrayList<>();
                mn.visitMaxs(0, 0);
            }
        }
    }

}
