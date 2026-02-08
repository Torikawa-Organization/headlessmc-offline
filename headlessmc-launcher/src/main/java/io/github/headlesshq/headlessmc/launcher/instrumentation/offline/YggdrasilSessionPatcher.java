package io.github.headlesshq.headlessmc.launcher.instrumentation.offline;

import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.RETURN;

import java.util.ArrayList;
import java.util.Locale;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import io.github.headlesshq.headlessmc.launcher.instrumentation.AbstractClassTransformer;
import io.github.headlesshq.headlessmc.launcher.instrumentation.EntryStream;
import io.github.headlesshq.headlessmc.launcher.instrumentation.Target;
import lombok.CustomLog;

/**
 * Patches Mojang's authlib to allow offline/invalid accounts to join
 * multiplayer servers. This transformer modifies the following classes:
 * <ul>
 * <li>{@code YggdrasilMinecraftSessionService} - makes {@code joinServer}
 * a no-op, preventing the client from authenticating with Mojang's
 * session servers when connecting to a multiplayer server.</li>
 * <li>{@code YggdrasilUserApiService} - makes {@code getKeyPair} return
 * {@code null}, preventing the 401 error when retrieving profile key
 * pairs for chat signing.</li>
 * </ul>
 * <p>
 * Note: Whether the server accepts the connection depends on the server's
 * configuration. Servers running in online-mode will still reject the
 * connection because Mojang's {@code hasJoinedServer} check will fail.
 * However, servers with online-mode=false, auth plugins, or proxy-based
 * authentication (e.g. BungeeCord) may accept the connection.
 */
@CustomLog
public class YggdrasilSessionPatcher extends AbstractClassTransformer {
    private static final String SESSION_SERVICE = "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService";
    private static final String USER_API_SERVICE = "com/mojang/authlib/yggdrasil/YggdrasilUserApiService";

    public YggdrasilSessionPatcher() {
        super(null);
    }

    @Override
    public boolean matches(Target target) {
        return target.getPath().toLowerCase(Locale.ENGLISH).contains("authlib");
    }

    @Override
    protected boolean matches(EntryStream stream) {
        if (!stream.getEntry().getName().endsWith(".class")) {
            return false;
        }

        String name = stream.getEntry().getName();
        name = name.substring(0, name.length() - 6);
        return SESSION_SERVICE.equals(name) || USER_API_SERVICE.equals(name);
    }

    @Override
    protected void transform(ClassNode cn) {
        if (SESSION_SERVICE.equals(cn.name)) {
            patchSessionService(cn);
        } else if (USER_API_SERVICE.equals(cn.name)) {
            patchUserApiService(cn);
        }
    }

    private void patchSessionService(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if ("joinServer".equals(mn.name)) {
                log.info("Patching " + cn.name + "." + mn.name + " to no-op for offline server support");
                makeVoidNoOp(mn);
            }
        }
    }

    private void patchUserApiService(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if ("getKeyPair".equals(mn.name)) {
                log.info("Patching " + cn.name + "." + mn.name + " to return null for offline server support");
                makeReturnNull(mn);
            }
        }
    }

    private void makeVoidNoOp(MethodNode mn) {
        InsnList insns = new InsnList();
        insns.add(new InsnNode(RETURN));
        mn.instructions = insns;
        mn.tryCatchBlocks = new ArrayList<>();
        mn.localVariables = new ArrayList<>();
        mn.parameters = new ArrayList<>();
        mn.visitMaxs(0, 0);
    }

    private void makeReturnNull(MethodNode mn) {
        InsnList insns = new InsnList();
        insns.add(new InsnNode(ACONST_NULL));
        insns.add(new InsnNode(ARETURN));
        mn.instructions = insns;
        mn.tryCatchBlocks = new ArrayList<>();
        mn.localVariables = new ArrayList<>();
        mn.parameters = new ArrayList<>();
        mn.visitMaxs(0, 0);
    }

}
