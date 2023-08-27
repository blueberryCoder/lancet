package me.ele.lancet.weaver.internal.asm;

import org.objectweb.asm.ClassReader;

import me.ele.lancet.weaver.ClassData;
import me.ele.lancet.weaver.internal.asm.classvisitor.HookClassVisitor;
import me.ele.lancet.weaver.internal.asm.classvisitor.InsertClassVisitor;
import me.ele.lancet.weaver.internal.asm.classvisitor.ProxyClassVisitor;
import me.ele.lancet.weaver.internal.asm.classvisitor.TryCatchInfoClassVisitor;
import me.ele.lancet.weaver.internal.entity.TransformInfo;
import me.ele.lancet.weaver.internal.graph.Graph;

/**
 * Created by Jude on 2017/4/25.
 */

public class ClassTransform {

    public static final String AID_INNER_CLASS_NAME = "_lancet";

    public static ClassData[] weave(TransformInfo transformInfo, Graph graph, byte[] classByte, String internalName) {

        ClassCollector classCollector = new ClassCollector(new ClassReader(classByte), graph);
        classCollector.setOriginClassName(internalName);
        // MethodChain与要织入的class是1:1关系
        MethodChain chain = new MethodChain(internalName, classCollector.getOriginClassVisitor(), graph);
        // ClassContext 与要织入的class是1:1关系
        ClassContext context = new ClassContext(graph, chain, classCollector.getOriginClassVisitor());
        // ClassTransform与要织入的class是1:1关系
        ClassTransform transform = new ClassTransform(classCollector, context);
        transform.connect(new HookClassVisitor(transformInfo.hookClasses)); // 跳过HookClass && 获得class的一些基本信息
        transform.connect(new ProxyClassVisitor(transformInfo.proxyInfo));  // 处理 Proxy
        transform.connect(new InsertClassVisitor(transformInfo.executeInfo)); //  处理 Insert
        transform.connect(new TryCatchInfoClassVisitor(transformInfo.tryCatchInfo)); // 处理TryCatch
        transform.startTransform();
        // 得到完成织入的字节码
        return classCollector.generateClassBytes();
    }

    private LinkedClassVisitor mHeadVisitor;
    private LinkedClassVisitor mTailVisitor;
    private ClassCollector mClassCollector;
    private final ClassContext context;

    public ClassTransform(ClassCollector mClassCollector, ClassContext context) {
        this.mClassCollector = mClassCollector;
        this.context = context;
    }

    void connect(LinkedClassVisitor visitor) {
        if (mHeadVisitor == null) {
            mHeadVisitor = visitor;
        } else {
            mTailVisitor.setNextClassVisitor(visitor);
        }
        mTailVisitor = visitor;
        visitor.setClassCollector(mClassCollector);
        visitor.setContext(context);
    }

    void startTransform() {
        mTailVisitor.setNextClassVisitor(mClassCollector.getOriginClassVisitor());
        mClassCollector.mClassReader.accept(mHeadVisitor, 0);
    }
}
