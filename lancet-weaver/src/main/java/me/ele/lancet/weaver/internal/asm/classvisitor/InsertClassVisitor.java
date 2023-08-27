package me.ele.lancet.weaver.internal.asm.classvisitor;

import me.ele.lancet.weaver.internal.asm.MethodChain;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.*;
import java.util.stream.Collectors;

import me.ele.lancet.weaver.internal.asm.ClassTransform;
import me.ele.lancet.weaver.internal.asm.LinkedClassVisitor;
import me.ele.lancet.weaver.internal.entity.InsertInfo;
import me.ele.lancet.weaver.internal.log.Log;
import me.ele.lancet.weaver.internal.util.TypeUtil;

/**
 * Created by gengwanpeng on 17/3/27.
 */
public class InsertClassVisitor extends LinkedClassVisitor {

    private Map<String, List<InsertInfo>> executeInfos;
    private List<InsertInfo> matched;


    public InsertClassVisitor(Map<String, List<InsertInfo>> executeInfos) {
        this.executeInfos = executeInfos;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        matched = executeInfos.get(getContext().name);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        if (matched != null) {
            List<InsertInfo> methodsMatched = new ArrayList<>(matched.size() >> 1);
            matched.removeIf(e -> {
                if (e.targetMethod.equals(name) && e.targetDesc.equals(desc)) {
                    if (((e.sourceMethod.access ^ access) & Opcodes.ACC_STATIC) != 0) {
                        throw new IllegalStateException(e.sourceClass + "." + e.sourceMethod.name + " should have the same static flag with "
                                + getContext().name + "." + name);
                    }
                    methodsMatched.add(e);
                    return true;
                }
                return false;
            });

            if (methodsMatched.size() > 0 && (access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0) {
                Log.tag("transform").i("visit Insert method: " + getContext().name + "." + name + " " + desc);

                String staticDesc = TypeUtil.descToStatic(access, desc, getContext().name);
                ClassVisitor cv = getClassCollector().getInnerClassVisitor(ClassTransform.AID_INNER_CLASS_NAME);
                String owner = getClassCollector().getCanonicalName(ClassTransform.AID_INNER_CLASS_NAME);

                String newName = name + "$___twin___";
                int newAccess = (access & ~(Opcodes.ACC_PROTECTED | Opcodes.ACC_PUBLIC)) | Opcodes.ACC_PRIVATE;

                MethodChain chain = getContext().getChain();
                chain.headFromInsert(newAccess, getContext().name, newName, desc);

                methodsMatched.forEach(e -> {
                    Log.tag("transform").i(
                            " from " + e.sourceClass + "." + e.sourceMethod.name);
                    String methodName = e.sourceClass.replace("/", "_") + "_" + e.sourceMethod.name;

                    // 生成lancet method，并生成调用指令Invoker
                    chain.next(owner, Opcodes.ACC_STATIC, methodName, staticDesc, e.threadLocalNode(), cv);
                });
                // 根据当前方法的签名，生成同签名函数，它的内容只是调用一下之前生成的lancet method (上次保留的header是个Invoker)
                chain.fakePreMethod(getContext().name, access, name, desc, signature, exceptions);

                return super.visitMethod(newAccess, newName, desc, signature, exceptions);
            }
        }
        return super.visitMethod(access, name, desc, signature, exceptions);
    }

    @Override
    public void visitEnd() {
        if (matched != null && matched.size() > 0) {
            new ArrayList<>(matched).stream()
                    .collect(Collectors.groupingBy(e -> e.targetMethod)).forEach((k, v) -> {
                if (v.stream().anyMatch(e -> e.createSuper)) { // 如果创建调用supper, 在所有匹配到的方法中，插入super指令
                    InsertInfo e = v.get(0);
                    MethodVisitor mv = visitMethod(e.sourceMethod.access, e.targetMethod, e.targetDesc, e.sourceMethod.signature,
                            (String[]) e.sourceMethod.exceptions.toArray(new String[0]));
                    GeneratorAdapter adapter = new GeneratorAdapter(mv, e.sourceMethod.access, e.targetMethod, e.targetDesc);
                    adapter.visitCode();
                    adapter.loadThis();
                    adapter.loadArgs();
                    adapter.visitMethodInsn(Opcodes.INVOKESPECIAL, getContext().superName, e.targetMethod, e.targetDesc, false);
                    adapter.returnValue();
                    int sz = Type.getArgumentsAndReturnSizes(e.targetDesc);
                    adapter.visitMaxs(Math.max(sz >> 2, sz & 3), sz >> 2);
                    adapter.visitEnd();
                }
            });

        }
        super.visitEnd();
    }
}
