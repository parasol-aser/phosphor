package edu.columbia.cs.psl.phosphor.instrumenter;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import edu.columbia.cs.psl.phosphor.BasicSourceSinkManager;
import edu.columbia.cs.psl.phosphor.Configuration;
import edu.columbia.cs.psl.phosphor.PreMain;
import edu.columbia.cs.psl.phosphor.SourceSinkManager;
import edu.columbia.cs.psl.phosphor.TaintUtils;
import edu.columbia.cs.psl.phosphor.runtime.TaintChecker;
import edu.columbia.cs.psl.phosphor.struct.TaintedPrimitiveWithIntTag;
import edu.columbia.cs.psl.phosphor.struct.TaintedPrimitiveWithObjTag;

public class SourceSinkTaintingMV extends MethodVisitor implements Opcodes {
	static SourceSinkManager sourceSinkManager = BasicSourceSinkManager.getInstance();

	String owner;
	String name;
	String desc;
	boolean thisIsASource;
	boolean thisIsASink;
	boolean thisIsTaintThrough;
	String origDesc;
	int access;
	boolean isStatic;
	Object lbl;

	public SourceSinkTaintingMV(MethodVisitor mv, int access, String owner, String name, String desc, String origDesc) {
		super(ASM5, mv);
		this.owner = owner;
		this.name = name;
		this.desc = desc;
		this.access = access;
		this.origDesc = origDesc;
		this.thisIsASource = sourceSinkManager.isSource(owner, name, desc);
		this.thisIsASink = sourceSinkManager.isSink(owner, name, desc);
		this.thisIsTaintThrough = sourceSinkManager.isTaintThrough(owner, name, desc);
		this.isStatic = (access & Opcodes.ACC_STATIC) != 0;
		if (this.thisIsASource) {
			lbl = sourceSinkManager.getLabel(owner, name, desc);
			if(PreMain.DEBUG)
				System.out.println("Source: " + owner + "." + name + desc + " Label: " + lbl);
		}
		if (PreMain.DEBUG) {
			if (this.thisIsASink)
				System.out.println("Sink: " + owner + "." + name + desc);
			if (this.thisIsTaintThrough)
				System.out.println("Taint through: " + owner + "." + name + desc);
		}
	}

	private void loadSourceLblAndMakeTaint() {
		if (Configuration.MULTI_TAINTING) {
			super.visitFieldInsn(GETSTATIC, Type.getInternalName(Configuration.class), "taintTagFactory", Type.getDescriptor(TaintTagFactory.class));
			super.visitLdcInsn(lbl);
			super.visitMethodInsn(INVOKEINTERFACE, Type.getInternalName(TaintTagFactory.class), "getAutoTaint", "(Ljava/lang/String;)"+Configuration.TAINT_TAG_DESC, true);
		} else {
			super.visitLdcInsn(lbl);
		}
	}

	@Override
	public void visitCode() {
		super.visitCode();
		if (this.thisIsASource || thisIsTaintThrough) {
			Type[] args = Type.getArgumentTypes(desc);
			int idx = 0;
			if (!isStatic)
				idx++;
			for (int i = 0; i < args.length; i++) {
				if (args[i].getSort() == Type.OBJECT) {
					super.visitVarInsn(ALOAD, idx);
					if(thisIsASource)
						loadSourceLblAndMakeTaint();
					else
					{
						super.visitVarInsn(ALOAD, 0);
						super.visitFieldInsn(GETFIELD, owner, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);
						if(Configuration.MULTI_TAINTING)
							super.visitMethodInsn(INVOKESTATIC, Configuration.TAINT_TAG_INTERNAL_NAME, "copyTaint", "("+Configuration.TAINT_TAG_DESC+")"+Configuration.TAINT_TAG_DESC, false);
					}
					super.visitMethodInsn(INVOKESTATIC, Type.getInternalName(TaintChecker.class), "setTaints", "(Ljava/lang/Object;" + Configuration.TAINT_TAG_DESC + ")V", false);
				} else if (args[i].getSort() == Type.ARRAY
						&& args[i].getElementType().getSort() == Type.OBJECT) {
					super.visitVarInsn(ALOAD, idx);
					if(thisIsASource)
						loadSourceLblAndMakeTaint();
					else
					{
						super.visitVarInsn(ALOAD, 0);
						super.visitFieldInsn(GETFIELD, owner, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);
						if(Configuration.MULTI_TAINTING)
							super.visitMethodInsn(INVOKESTATIC, Configuration.TAINT_TAG_INTERNAL_NAME, "copyTaint", "("+Configuration.TAINT_TAG_DESC+")"+Configuration.TAINT_TAG_DESC, false);
					}
					super.visitMethodInsn(INVOKESTATIC, Type.getInternalName(TaintChecker.class), "setTaints", "(" + Configuration.TAINT_TAG_ARRAYDESC + Configuration.TAINT_TAG_DESC + ")V", false);
				}
				idx += args[i].getSize();
			}
		}
		if (sourceSinkManager.isSink(owner, name, desc)) {
			//TODO - check every arg to see if is taint tag
			Type[] args = Type.getArgumentTypes(desc);
			int idx = 0;
			if (!isStatic)
				idx++;
			boolean skipNextPrimitive = false;
			for (int i = 0; i < args.length; i++) {
				if ((args[i].getSort() == Type.OBJECT && !args[i].getDescriptor().equals(Configuration.TAINT_TAG_DESC)) || args[i].getSort() == Type.ARRAY) {
					if (args[i].getSort() == Type.ARRAY && (args[i].getElementType().getSort() != Type.OBJECT || args[i].getDescriptor().equals(Configuration.TAINT_TAG_ARRAYDESC))
							&& args[i].getDimensions() == 1) {
						if (!skipNextPrimitive) {
							super.visitVarInsn(ALOAD, idx);
							super.visitMethodInsn(INVOKESTATIC, Type.getInternalName(TaintChecker.class), "checkTaint", "(Ljava/lang/Object;)V", false);
						}
						skipNextPrimitive = !skipNextPrimitive;
					} else {
						super.visitVarInsn(ALOAD, idx);
						super.visitMethodInsn(INVOKESTATIC, Type.getInternalName(TaintChecker.class), "checkTaint", "(Ljava/lang/Object;)V", false);
					}
				} else if (!skipNextPrimitive) {
					super.visitVarInsn(Configuration.TAINT_LOAD_OPCODE, idx);
					super.visitMethodInsn(INVOKESTATIC, Type.getInternalName(TaintChecker.class), "checkTaint", "(" + Configuration.TAINT_TAG_DESC + ")V", false);
					skipNextPrimitive = true;
				} else if (skipNextPrimitive)
					skipNextPrimitive = false;
				idx += args[i].getSize();
			}
		}
	}

	@Override
	public void visitInsn(int opcode) {
		if (opcode == ARETURN && (this.thisIsASource || this.thisIsTaintThrough)) {
			Type returnType = Type.getReturnType(this.origDesc);
			if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
				super.visitInsn(DUP);
				if(thisIsASource)
					loadSourceLblAndMakeTaint();
				else
				{
					super.visitVarInsn(ALOAD, 0);
					super.visitFieldInsn(GETFIELD, owner, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);
					if(Configuration.MULTI_TAINTING)
						super.visitMethodInsn(INVOKESTATIC, Configuration.TAINT_TAG_INTERNAL_NAME, "copyTaint", "("+Configuration.TAINT_TAG_DESC+")"+Configuration.TAINT_TAG_DESC, false);
				}
				super.visitMethodInsn(INVOKESTATIC, Type.getInternalName(TaintChecker.class), "setTaints", "(Ljava/lang/Object;" + Configuration.TAINT_TAG_DESC + ")V", false);
			} else if (returnType.getSort() == Type.VOID) {

			} else {
				// primitive
				super.visitInsn(DUP);
				if(thisIsASource)
					loadSourceLblAndMakeTaint();
				else
				{
					super.visitVarInsn(ALOAD, 0);
					super.visitFieldInsn(GETFIELD, owner, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);
					if(Configuration.MULTI_TAINTING)
						super.visitMethodInsn(INVOKESTATIC, Configuration.TAINT_TAG_INTERNAL_NAME, "copyTaint", "("+Configuration.TAINT_TAG_DESC+")"+Configuration.TAINT_TAG_DESC, false);
				}
				if (Configuration.MULTI_TAINTING)
					super.visitFieldInsn(PUTFIELD, Type.getInternalName(TaintedPrimitiveWithObjTag.class), "taint", Configuration.TAINT_TAG_DESC);
				else
					super.visitFieldInsn(PUTFIELD, Type.getInternalName(TaintedPrimitiveWithIntTag.class), "taint", "I");
			}
		}
	
		super.visitInsn(opcode);
	}
}
