package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the gated complex-number runtime helpers of the JVM backend: the {@code _c*}
 * methods a compiled program calls when it can observe a complex value. Every helper
 * constructs (or threads) the travelling {@code am.ik.rontolisp.runtime.RontoComplex}
 * holder, so the whole group -- methods and holder class file alike -- is emitted only
 * when the program may create a complex, and a program without one compiles
 * byte-identically to a build that never knew about them (`.kb/jvm-complex.md`).
 *
 * <p>
 * The part-wise real arithmetic reuses the unconditional numeric helpers ({@code _add},
 * {@code _sub}, {@code _mul}, {@code _div}, {@code _neg}, {@code _dbl}, {@code _cmp}) by
 * name, so the coercion funnels and their error texts stay identical to real arithmetic.
 * Exactness follows the interpreter ({@code Environment}): all-rational parts compute
 * exactly, a float anywhere coerces the whole step to doubles, and every result
 * canonicalizes through {@code _ccomplex} (a rational zero imaginary part demotes to the
 * real, a float zero stays complex).
 */
final class JvmComplexRuntimeBuilder {

	/** The canonical constructor: {@code _ccomplex(real, imag)}. */
	static final String COMPLEX = "_ccomplex";

	/** Complex addition over real-or-complex operands. */
	static final String ADD = "_cadd";

	/** Complex subtraction over real-or-complex operands. */
	static final String SUB = "_csub";

	/** Complex multiplication over real-or-complex operands. */
	static final String MUL = "_cmul";

	/** Complex division over real-or-complex operands. */
	static final String DIV = "_cdiv";

	/**
	 * Complex negation. A separate helper (rather than {@code _csub} from zero) because
	 * {@code 0 - x} and {@code -x} differ on signed zeros: {@code 0.0 -
	 * 0.0} is {@code +0.0} while {@code -0.0} stays {@code -0.0}.
	 */
	static final String NEG = "_cneg";

	/** The principal square root, rooting negatives into the plane. */
	static final String SQRT = "_csqrt";

	/** Complex-capable power. */
	static final String POW = "_cpow";

	/**
	 * The unary math functions over real-or-complex operands, selected by an {@code int}
	 * opcode ({@link #U1_EXP} and friends).
	 */
	static final String U1 = "_cu1";

	/** Complex conjugation. */
	static final String CONJUGATE = "_cconjugate";

	/**
	 * Like {@code _cmpb} but a complex operand signals the interpreter's "Expected real
	 * number" text: the ordering operators' comparison once a complex literal steered
	 * them off the double path.
	 */
	static final String CCPMB = "_ccmpb";

	/** {@code phase} over real-or-complex operands (an angle, always a double). */
	static final String CPHASE = "_cphase";

	/** {@link #U1} selector for {@code exp}. */
	static final int U1_EXP = 0;

	/** {@link #U1} selector for {@code log}. */
	static final int U1_LOG = 1;

	/** {@link #U1} selector for {@code sin}. */
	static final int U1_SIN = 2;

	/** {@link #U1} selector for {@code cos}. */
	static final int U1_COS = 3;

	/** {@link #U1} selector for {@code tan}. */
	static final int U1_TAN = 4;

	/** {@link #U1} selector for {@code asin}. */
	static final int U1_ASIN = 5;

	/** {@link #U1} selector for {@code acos}. */
	static final int U1_ACOS = 6;

	/** {@link #U1} selector for {@code atan}. */
	static final int U1_ATAN = 7;

	/** {@link #U1} selector for {@code sinh}. */
	static final int U1_SINH = 8;

	/** {@link #U1} selector for {@code cosh}. */
	static final int U1_COSH = 9;

	/** {@link #U1} selector for {@code tanh}. */
	static final int U1_TANH = 10;

	/**
	 * The helper names this group owns, for {@code JvmLispCompiler.gateGroupFor}: a
	 * finished class calling one of these without it having been emitted re-runs with the
	 * complex group forced on.
	 */
	static final Set<String> METHOD_NAMES = Set.of(COMPLEX, ADD, SUB, MUL, DIV, NEG, SQRT, POW, U1, CONJUGATE, CCPMB,
			CPHASE);

	/**
	 * The class files that travel beside a compiled program using this group
	 * (`.kb/jvm-export.md`, "What travels").
	 */
	static final List<String> RUNTIME_CLASS_FILES = List.of("am/ik/rontolisp/runtime/RontoComplex.class");

	private static final String OBJ = "Ljava/lang/Object;";

	private static final String BINARY_DESC = "(" + OBJ + OBJ + ")" + OBJ;

	private static final String UNARY_DESC = "(" + OBJ + ")" + OBJ;

	private static final String U1_DESC = "(Ljava/lang/Object;I)Ljava/lang/Object;";

	private static final String CMP_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)I";

	/**
	 * The method descriptor for a helper name: the one source of truth shared by the
	 * group emission and the call-site references (a mismatch fails the post-compile
	 * self-check loudly instead of linking).
	 */
	static String descFor(String op) {
		if (U1.equals(op)) {
			return U1_DESC;
		}
		if (CCPMB.equals(op)) {
			return CMP_DESC;
		}
		if (SQRT.equals(op) || CONJUGATE.equals(op) || NEG.equals(op) || CPHASE.equals(op)) {
			return UNARY_DESC;
		}
		return BINARY_DESC;
	}

	private JvmComplexRuntimeBuilder() {
	}

	/**
	 * A generated complex helper method.
	 *
	 * @param nameUtf8 the method name constant
	 * @param descUtf8 the method descriptor constant
	 * @param code the method bytecode
	 * @param maxStack the operand stack size
	 * @param maxLocals the local variable slot count
	 * @param exceptionTable the exception table entries, each as {@code {startPc, endPc,
	 * handlerPc, catchTypeIndex}}
	 */
	record ComplexMethod(Utf8Constant nameUtf8, Utf8Constant descUtf8, List<Integer> code, int maxStack, int maxLocals,
			List<int[]> exceptionTable) {
	}

	/**
	 * The generated complex runtime: the helper methods to emit and the references that
	 * compiled code invokes.
	 *
	 * @param methods the helper methods to emit into the class
	 * @param ops the invokable helper references, keyed by operation
	 */
	record ComplexRuntime(List<ComplexMethod> methods, Map<String, MethodrefConstant> ops) {
	}

	/**
	 * Reads {@link #RUNTIME_CLASS_FILES} off the compiler's own classpath.
	 * @return each class file's path within an output tree (or jar), mapped to its bytes
	 */
	static Map<String, byte[]> runtimeClassFiles() {
		return JvmRuntimeClassFiles.read(RUNTIME_CLASS_FILES);
	}

	/** Shared constant-pool references for the complex helpers. */
	private record Refs(ClassConstant thisClass, ClassConstant rcClass, FieldrefConstant rcReal,
			FieldrefConstant rcImag, MethodrefConstant rcInit, ClassConstant longClass, ClassConstant doubleClass,
			ClassConstant bigClass, ClassConstant ratArrClass, ClassConstant numberClass, ClassConstant mathClass,
			ClassConstant rteClass, MethodrefConstant longValueOf, MethodrefConstant longValue,
			MethodrefConstant doubleValueOf, MethodrefConstant numDoubleValue, MethodrefConstant rteInit,
			MethodrefConstant strConcat, MethodrefConstant lispToString, ConstantPool.StringConstant numPrefix,
			ConstantPool.StringConstant realPrefix, MethodrefConstant rAdd, MethodrefConstant rSub,
			MethodrefConstant rMul, MethodrefConstant rDiv, MethodrefConstant rNeg, MethodrefConstant rDbl,
			MethodrefConstant rCmp, MethodrefConstant rCComplex, MethodrefConstant rCMul, MethodrefConstant rCDiv) {
	}

	/**
	 * Builds all complex helper methods and registers their constant-pool entries.
	 * @param cp the constant pool to populate
	 * @param thisClass the generated class
	 * @return the helper methods and the invokable references compiled code calls
	 */
	static ComplexRuntime build(ConstantPool cp, ClassConstant thisClass) {
		ClassConstant rcClass = cp.addClass(cp.addUtf8("am/ik/rontolisp/runtime/RontoComplex"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		ClassConstant bigClass = cp.addClass(cp.addUtf8("java/math/BigInteger"));
		ClassConstant ratArrClass = cp.addClass(cp.addUtf8("[Ljava/math/BigInteger;"));
		ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
		ClassConstant mathClass = cp.addClass(cp.addUtf8("java/lang/Math"));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		ClassConstant rteClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		Refs refs = new Refs(thisClass, rcClass,
				cp.addFieldref(rcClass, cp.addNameAndType(cp.addUtf8("real"), cp.addUtf8(OBJ))),
				cp.addFieldref(rcClass, cp.addNameAndType(cp.addUtf8("imag"), cp.addUtf8(OBJ))),
				cp.addMethodref(rcClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(" + OBJ + OBJ + ")V"))),
				longClass, doubleClass, bigClass, ratArrClass, numberClass, mathClass, rteClass,
				cp.addMethodref(longClass, cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;"))),
				cp.addMethodref(longClass, cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J"))),
				cp.addMethodref(doubleClass,
						cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;"))),
				cp.addMethodref(numberClass, cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D"))),
				cp.addMethodref(rteClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V"))),
				cp.addMethodref(stringClass,
						cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;"))),
				cp.addMethodref(thisClass,
						cp.addNameAndType(cp.addUtf8("_lispToString"),
								cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/String;"))),
				cp.addString(am.ik.rontolisp.ClosRegistry.EXPECTED_NUMBER_MESSAGE_PREFIX),
				cp.addString(am.ik.rontolisp.ClosRegistry.EXPECTED_REAL_MESSAGE_PREFIX),
				self(cp, thisClass, JvmNumericRuntimeBuilder.ADD, BINARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.SUB, BINARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.MUL, BINARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.DIV, BINARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.NEG, UNARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.DBL, UNARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.CMP, CMP_DESC), self(cp, thisClass, COMPLEX, BINARY_DESC),
				self(cp, thisClass, MUL, BINARY_DESC), self(cp, thisClass, DIV, BINARY_DESC));
		List<ComplexMethod> methods = new ArrayList<>();
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		addMethod(cp, thisClass, methods, ops, COMPLEX, BINARY_DESC,
				buildComplex(refs, cp.addUtf8(COMPLEX), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, ADD, BINARY_DESC,
				buildAdd(refs, cp.addUtf8(ADD), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, SUB, BINARY_DESC,
				buildSub(refs, cp.addUtf8(SUB), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, MUL, BINARY_DESC,
				buildMul(refs, cp.addUtf8(MUL), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, DIV, BINARY_DESC,
				buildDiv(refs, cp.addUtf8(DIV), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, NEG, UNARY_DESC,
				buildNeg(refs, cp.addUtf8(NEG), cp.addUtf8(UNARY_DESC)));
		addMethod(cp, thisClass, methods, ops, SQRT, UNARY_DESC,
				buildSqrt(refs, cp, cp.addUtf8(SQRT), cp.addUtf8(UNARY_DESC)));
		addMethod(cp, thisClass, methods, ops, POW, BINARY_DESC,
				buildPow(refs, cp, cp.addUtf8(POW), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, U1, U1_DESC, buildU1(refs, cp, cp.addUtf8(U1), cp.addUtf8(U1_DESC)));
		addMethod(cp, thisClass, methods, ops, CONJUGATE, UNARY_DESC,
				buildConjugate(refs, cp.addUtf8(CONJUGATE), cp.addUtf8(UNARY_DESC)));
		addMethod(cp, thisClass, methods, ops, CCPMB, CMP_DESC,
				buildCCmpBits(refs, cp, cp.addUtf8(CCPMB), cp.addUtf8(CMP_DESC)));
		addMethod(cp, thisClass, methods, ops, CPHASE, UNARY_DESC,
				buildCPhase(refs, cp, cp.addUtf8(CPHASE), cp.addUtf8(UNARY_DESC)));
		return new ComplexRuntime(methods, ops);
	}

	private static MethodrefConstant self(ConstantPool cp, ClassConstant thisClass, String name, String desc) {
		return cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
	}

	private static void addMethod(ConstantPool cp, ClassConstant thisClass, List<ComplexMethod> methods,
			Map<String, MethodrefConstant> ops, String name, String desc, ComplexMethod method) {
		methods.add(method);
		ops.put(name, cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc))));
	}

	// ------------------------------------------------------------------
	// Emission helpers (raw List<Integer> code, JvmNumericRuntimeBuilder idiom)

	private static void emitU2(List<Integer> c, int index) {
		JvmRuntimeBuilder.emitU2(c, index);
	}

	private static void patch(List<Integer> c, int pos) {
		JvmRuntimeBuilder.patchBranch(c, pos, c.size());
	}

	private static void emitLdc(List<Integer> c, int index) {
		JvmRuntimeBuilder.emitLdc(c, index);
	}

	private static int jump(List<Integer> c, int opcode) {
		int pos = c.size();
		c.add(opcode);
		emitU2(c, 0);
		return pos;
	}

	/** Pushes a small int constant (-1..5, byte, short). */
	private static void emitInt(List<Integer> c, int value) {
		if (value == -1) {
			c.add(Opcode.ICONST_M1);
		}
		else if (value == 0) {
			c.add(Opcode.ICONST_0);
		}
		else if (value == 1) {
			c.add(Opcode.ICONST_1);
		}
		else if (value == 2) {
			c.add(Opcode.ICONST_2);
		}
		else if (value == 3) {
			c.add(Opcode.ICONST_3);
		}
		else if (value == 4) {
			c.add(Opcode.ICONST_4);
		}
		else if (value == 5) {
			c.add(Opcode.ICONST_5);
		}
		else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
			c.add(Opcode.BIPUSH);
			c.add(value & 0xFF);
		}
		else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			c.add(Opcode.SIPUSH);
			c.add((value >> 8) & 0xFF);
			c.add(value & 0xFF);
		}
		else {
			throw new IllegalArgumentException("int constant out of range: " + value);
		}
	}

	private static void aload(List<Integer> c, int slot) {
		if (slot == 0) {
			c.add(Opcode.ALOAD_0);
		}
		else if (slot == 1) {
			c.add(Opcode.ALOAD_1);
		}
		else if (slot == 2) {
			c.add(Opcode.ALOAD_2);
		}
		else if (slot == 3) {
			c.add(Opcode.ALOAD_3);
		}
		else {
			c.add(Opcode.ALOAD);
			c.add(slot);
		}
	}

	private static void astore(List<Integer> c, int slot) {
		if (slot == 0) {
			c.add(Opcode.ASTORE_0);
		}
		else if (slot == 1) {
			c.add(Opcode.ASTORE_1);
		}
		else if (slot == 2) {
			c.add(Opcode.ASTORE_2);
		}
		else if (slot == 3) {
			c.add(Opcode.ASTORE_3);
		}
		else {
			c.add(Opcode.ASTORE);
			c.add(slot);
		}
	}

	private static void dload(List<Integer> c, int slot) {
		if (slot == 0) {
			c.add(Opcode.DLOAD_0);
		}
		else if (slot == 1) {
			c.add(Opcode.DLOAD_1);
		}
		else if (slot == 2) {
			c.add(Opcode.DLOAD_2);
		}
		else if (slot == 3) {
			c.add(Opcode.DLOAD_3);
		}
		else {
			c.add(Opcode.DLOAD);
			c.add(slot);
		}
	}

	private static void dstore(List<Integer> c, int slot) {
		if (slot == 0) {
			c.add(Opcode.DSTORE_0);
		}
		else if (slot == 1) {
			c.add(Opcode.DSTORE_1);
		}
		else if (slot == 2) {
			c.add(Opcode.DSTORE_2);
		}
		else if (slot == 3) {
			c.add(Opcode.DSTORE_3);
		}
		else {
			c.add(Opcode.DSTORE);
			c.add(slot);
		}
	}

	private static void iload(List<Integer> c, int slot) {
		if (slot == 0) {
			c.add(Opcode.ILOAD_0);
		}
		else if (slot == 1) {
			c.add(Opcode.ILOAD_1);
		}
		else if (slot == 2) {
			c.add(Opcode.ILOAD_2);
		}
		else if (slot == 3) {
			c.add(Opcode.ILOAD_3);
		}
		else {
			c.add(Opcode.ILOAD);
			c.add(slot);
		}
	}

	private static void istore(List<Integer> c, int slot) {
		if (slot == 0) {
			c.add(Opcode.ISTORE_0);
		}
		else if (slot == 1) {
			c.add(Opcode.ISTORE_1);
		}
		else if (slot == 2) {
			c.add(Opcode.ISTORE_2);
		}
		else if (slot == 3) {
			c.add(Opcode.ISTORE_3);
		}
		else {
			c.add(Opcode.ISTORE);
			c.add(slot);
		}
	}

	private static void lload(List<Integer> c, int slot) {
		if (slot == 0) {
			c.add(Opcode.LLOAD_0);
		}
		else if (slot == 1) {
			c.add(Opcode.LLOAD_1);
		}
		else if (slot == 2) {
			c.add(Opcode.LLOAD_2);
		}
		else if (slot == 3) {
			c.add(Opcode.LLOAD_3);
		}
		else {
			c.add(Opcode.LLOAD);
			c.add(slot);
		}
	}

	private static void lstore(List<Integer> c, int slot) {
		if (slot == 0) {
			c.add(Opcode.LSTORE_0);
		}
		else if (slot == 1) {
			c.add(Opcode.LSTORE_1);
		}
		else if (slot == 2) {
			c.add(Opcode.LSTORE_2);
		}
		else if (slot == 3) {
			c.add(Opcode.LSTORE_3);
		}
		else {
			c.add(Opcode.LSTORE);
			c.add(slot);
		}
	}

	/** A self-call to a precomputed helper reference. */
	private static void call(List<Integer> c, MethodrefConstant ref) {
		c.add(Opcode.INVOKESTATIC);
		emitU2(c, ref.index());
	}

	private static void callMath(List<Integer> c, Refs refs, ConstantPool cp, String name, String desc) {
		c.add(Opcode.INVOKESTATIC);
		emitU2(c, cp.addMethodref(refs.mathClass(), cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc))).index());
	}

	/**
	 * Unboxes the real value in {@code slot} to a raw double ({@code _dbl} plus
	 * {@code Number.doubleValue}, throwing the interpreter's "Expected number" text for a
	 * non-number).
	 */
	private static void emitToDouble(List<Integer> c, Refs refs, int slot) {
		aload(c, slot);
		call(c, refs.rDbl());
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.numberClass().index());
		c.add(Opcode.INVOKEVIRTUAL);
		emitU2(c, refs.numDoubleValue().index());
	}

	/** Boxes the raw double on top of the stack. */
	private static void emitBoxDouble(List<Integer> c, Refs refs) {
		c.add(Opcode.INVOKESTATIC);
		emitU2(c, refs.doubleValueOf().index());
	}

	/**
	 * Emits {@code throw new RuntimeException("Expected number, got: " +
	 * _lispToString(value))} for the value in {@code slot}.
	 */
	private static void emitNumberErrThrow(List<Integer> c, Refs refs, int slot) {
		c.add(Opcode.NEW);
		emitU2(c, refs.rteClass().index());
		c.add(Opcode.DUP);
		emitLdc(c, refs.numPrefix().index());
		aload(c, slot);
		c.add(Opcode.INVOKESTATIC);
		emitU2(c, refs.lispToString().index());
		c.add(Opcode.INVOKEVIRTUAL);
		emitU2(c, refs.strConcat().index());
		c.add(Opcode.INVOKESPECIAL);
		emitU2(c, refs.rteInit().index());
		c.add(Opcode.ATHROW);
	}

	/**
	 * Emits the real-part test for the value in {@code slot}: falls through when it is a
	 * {@code Long}, {@code BigInteger}, {@code BigInteger[]} or {@code Double}, otherwise
	 * jumps to {@code onFailure}.
	 */
	private static void emitRequireReal(List<Integer> c, Refs refs, int slot, List<Integer> onFailure) {
		aload(c, slot);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.longClass().index());
		onFailure.add(jumpIfTrue(c));
		aload(c, slot);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.bigClass().index());
		onFailure.add(jumpIfTrue(c));
		aload(c, slot);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.ratArrClass().index());
		onFailure.add(jumpIfTrue(c));
		aload(c, slot);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.doubleClass().index());
		onFailure.add(jumpIfTrue(c));
	}

	private static int jumpIfTrue(List<Integer> c) {
		return jump(c, Opcode.IFNE);
	}

	/**
	 * Extracts the complex parts of the value in {@code paramSlot} into
	 * {@code reSlot}/{@code imSlot}: a holder answers its fields, a {@code Double}
	 * answers itself and a {@code 0.0} imaginary part, any other value answers itself and
	 * an integer zero (the caller funnels non-reals through the real operators, exactly
	 * like {@code Environment.complexReal/complexImag}).
	 */
	private static void emitExtractParts(List<Integer> c, Refs refs, int paramSlot, int reSlot, int imSlot) {
		aload(c, paramSlot);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.rcClass().index());
		int notHolderRe = jump(c, Opcode.IFEQ);
		aload(c, paramSlot);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcReal().index());
		astore(c, reSlot);
		int doneRe = jump(c, Opcode.GOTO);
		patch(c, notHolderRe);
		aload(c, paramSlot);
		astore(c, reSlot);
		patch(c, doneRe);
		aload(c, paramSlot);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.rcClass().index());
		int notHolderIm = jump(c, Opcode.IFEQ);
		aload(c, paramSlot);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcImag().index());
		astore(c, imSlot);
		int doneIm = jump(c, Opcode.GOTO);
		patch(c, notHolderIm);
		aload(c, paramSlot);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.doubleClass().index());
		int notDouble = jump(c, Opcode.IFEQ);
		c.add(Opcode.DCONST_0);
		emitBoxDouble(c, refs);
		astore(c, imSlot);
		int doneZero = jump(c, Opcode.GOTO);
		patch(c, notDouble);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.INVOKESTATIC);
		emitU2(c, refs.longValueOf().index());
		astore(c, imSlot);
		patch(c, doneZero);
		patch(c, doneIm);
	}

	/**
	 * Tests whether any of the four parts in the given slots is a {@code Double},
	 * answering the branch positions to patch to the float path (execution falls through
	 * to the exact path).
	 */
	private static List<Integer> emitFloatTest(List<Integer> c, Refs refs, int[] slots) {
		List<Integer> toFloat = new ArrayList<>();
		for (int slot : slots) {
			aload(c, slot);
			c.add(Opcode.INSTANCEOF);
			emitU2(c, refs.doubleClass().index());
			toFloat.add(jump(c, Opcode.IFNE));
		}
		return toFloat;
	}

	/**
	 * Constructs a holder from the two parts in {@code reSlot}/{@code imSlot}.
	 */
	private static void emitNewHolderFromSlots(List<Integer> c, Refs refs, int reSlot, int imSlot) {
		c.add(Opcode.NEW);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.DUP);
		aload(c, reSlot);
		aload(c, imSlot);
		c.add(Opcode.INVOKESPECIAL);
		emitU2(c, refs.rcInit().index());
	}

	// _ccomplex(Object real, Object imag): the canonical value. A non-real part
	// signals "Expected number"; a float anywhere coerces both parts to floats (a
	// float zero never demotes); a rational zero imaginary part demotes to the
	// real itself; otherwise a fresh holder.
	private static ComplexMethod buildComplex(Refs refs, Utf8Constant name, Utf8Constant desc) {
		List<Integer> c = new ArrayList<>();
		List<Integer> realOk = new ArrayList<>();
		emitRequireReal(c, refs, 0, realOk);
		emitNumberErrThrow(c, refs, 0);
		for (int pos : realOk) {
			patch(c, pos);
		}
		List<Integer> imagOk = new ArrayList<>();
		emitRequireReal(c, refs, 1, imagOk);
		emitNumberErrThrow(c, refs, 1);
		for (int pos : imagOk) {
			patch(c, pos);
		}
		aload(c, 0);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.doubleClass().index());
		int realIsDouble = jump(c, Opcode.IFNE);
		aload(c, 1);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.doubleClass().index());
		int imagIsDouble = jump(c, Opcode.IFNE);
		// Exact path: a rational zero imaginary part demotes to the real.
		aload(c, 1);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.INVOKESTATIC);
		emitU2(c, refs.longValueOf().index());
		call(c, refs.rCmp());
		c.add(Opcode.ICONST_0);
		int notZero = jump(c, Opcode.IF_ICMPNE);
		aload(c, 0);
		c.add(Opcode.ARETURN);
		patch(c, notZero);
		emitNewHolderFromSlots(c, refs, 0, 1);
		c.add(Opcode.ARETURN);
		// Float path: both parts through _dbl (a Double answers as-is).
		patch(c, realIsDouble);
		patch(c, imagIsDouble);
		aload(c, 0);
		call(c, refs.rDbl());
		astore(c, 0);
		aload(c, 1);
		call(c, refs.rDbl());
		astore(c, 1);
		emitNewHolderFromSlots(c, refs, 0, 1);
		c.add(Opcode.ARETURN);
		return new ComplexMethod(name, desc, c, 6, 2, List.of());
	}

	// _cadd/_csub over real-or-complex operands. All-rational parts compute
	// exactly through _add/_sub; a float anywhere coerces the step to doubles.
	// Slots: params 0-1, parts 2-5, boxed results 6-7.
	private static ComplexMethod buildAdd(Refs refs, Utf8Constant name, Utf8Constant desc) {
		return buildAddSub(refs, name, desc, Opcode.DADD, JvmNumericRuntimeBuilder.ADD);
	}

	private static ComplexMethod buildSub(Refs refs, Utf8Constant name, Utf8Constant desc) {
		return buildAddSub(refs, name, desc, Opcode.DSUB, JvmNumericRuntimeBuilder.SUB);
	}

	private static ComplexMethod buildAddSub(Refs refs, Utf8Constant name, Utf8Constant desc, int doubleOpcode,
			String exactOp) {
		List<Integer> c = new ArrayList<>();
		emitExtractParts(c, refs, 0, 2, 3);
		emitExtractParts(c, refs, 1, 4, 5);
		List<Integer> toFloat = emitFloatTest(c, refs, new int[] { 2, 3, 4, 5 });
		aload(c, 2);
		aload(c, 4);
		call(c, exactOpRef(refs, exactOp));
		aload(c, 3);
		aload(c, 5);
		call(c, exactOpRef(refs, exactOp));
		call(c, refs.rCComplex());
		c.add(Opcode.ARETURN);
		for (int pos : toFloat) {
			patch(c, pos);
		}
		emitToDouble(c, refs, 2);
		emitToDouble(c, refs, 4);
		c.add(doubleOpcode);
		emitBoxDouble(c, refs);
		astore(c, 6);
		emitToDouble(c, refs, 3);
		emitToDouble(c, refs, 5);
		c.add(doubleOpcode);
		emitBoxDouble(c, refs);
		astore(c, 7);
		emitNewHolderFromSlots(c, refs, 6, 7);
		c.add(Opcode.ARETURN);
		return new ComplexMethod(name, desc, c, 6, 8, List.of());
	}

	private static MethodrefConstant exactOpRef(Refs refs, String exactOp) {
		if (JvmNumericRuntimeBuilder.ADD.equals(exactOp)) {
			return refs.rAdd();
		}
		if (JvmNumericRuntimeBuilder.SUB.equals(exactOp)) {
			return refs.rSub();
		}
		if (JvmNumericRuntimeBuilder.MUL.equals(exactOp)) {
			return refs.rMul();
		}
		return refs.rDiv();
	}

	// _cmul over real-or-complex operands: (a+bi)(c+di) = (ac-bd, ad+bc).
	// Slots: params 0-1, parts 2-5, boxed results 6-7, doubles 8-15.
	private static ComplexMethod buildMul(Refs refs, Utf8Constant name, Utf8Constant desc) {
		List<Integer> c = new ArrayList<>();
		emitExtractParts(c, refs, 0, 2, 3);
		emitExtractParts(c, refs, 1, 4, 5);
		List<Integer> toFloat = emitFloatTest(c, refs, new int[] { 2, 3, 4, 5 });
		aload(c, 2);
		aload(c, 4);
		call(c, refs.rMul());
		aload(c, 3);
		aload(c, 5);
		call(c, refs.rMul());
		call(c, refs.rSub());
		astore(c, 6);
		aload(c, 2);
		aload(c, 5);
		call(c, refs.rMul());
		aload(c, 3);
		aload(c, 4);
		call(c, refs.rMul());
		call(c, refs.rAdd());
		astore(c, 7);
		aload(c, 6);
		aload(c, 7);
		call(c, refs.rCComplex());
		c.add(Opcode.ARETURN);
		int end = jump(c, Opcode.GOTO);
		for (int pos : toFloat) {
			patch(c, pos);
		}
		emitToDouble(c, refs, 2);
		dstore(c, 8);
		emitToDouble(c, refs, 3);
		dstore(c, 10);
		emitToDouble(c, refs, 4);
		dstore(c, 12);
		emitToDouble(c, refs, 5);
		dstore(c, 14);
		dload(c, 8);
		dload(c, 12);
		c.add(Opcode.DMUL);
		dload(c, 10);
		dload(c, 14);
		c.add(Opcode.DMUL);
		c.add(Opcode.DSUB);
		emitBoxDouble(c, refs);
		astore(c, 6);
		dload(c, 8);
		dload(c, 14);
		c.add(Opcode.DMUL);
		dload(c, 10);
		dload(c, 12);
		c.add(Opcode.DMUL);
		c.add(Opcode.DADD);
		emitBoxDouble(c, refs);
		astore(c, 7);
		emitNewHolderFromSlots(c, refs, 6, 7);
		c.add(Opcode.ARETURN);
		return new ComplexMethod(name, desc, c, 6, 16, List.of());
	}

	// _cdiv over real-or-complex operands. The exact path divides by the
	// c^2+d^2 denominator through _div, whose _rat landing throws the same
	// ArithmeticException("Division by zero") a real division throws.
	// Slots: params 0-1, parts 2-5, temps 6-8, doubles 10-19.
	private static ComplexMethod buildDiv(Refs refs, Utf8Constant name, Utf8Constant desc) {
		List<Integer> c = new ArrayList<>();
		emitExtractParts(c, refs, 0, 2, 3);
		emitExtractParts(c, refs, 1, 4, 5);
		List<Integer> toFloat = emitFloatTest(c, refs, new int[] { 2, 3, 4, 5 });
		aload(c, 4);
		aload(c, 4);
		call(c, refs.rMul());
		aload(c, 5);
		aload(c, 5);
		call(c, refs.rMul());
		call(c, refs.rAdd());
		astore(c, 6);
		aload(c, 2);
		aload(c, 4);
		call(c, refs.rMul());
		aload(c, 3);
		aload(c, 5);
		call(c, refs.rMul());
		call(c, refs.rAdd());
		aload(c, 6);
		call(c, refs.rDiv());
		astore(c, 7);
		aload(c, 3);
		aload(c, 4);
		call(c, refs.rMul());
		aload(c, 2);
		aload(c, 5);
		call(c, refs.rMul());
		call(c, refs.rSub());
		aload(c, 6);
		call(c, refs.rDiv());
		astore(c, 8);
		aload(c, 7);
		aload(c, 8);
		call(c, refs.rCComplex());
		c.add(Opcode.ARETURN);
		for (int pos : toFloat) {
			patch(c, pos);
		}
		emitToDouble(c, refs, 2);
		dstore(c, 10);
		emitToDouble(c, refs, 3);
		dstore(c, 12);
		emitToDouble(c, refs, 4);
		dstore(c, 14);
		emitToDouble(c, refs, 5);
		dstore(c, 16);
		dload(c, 14);
		dload(c, 14);
		c.add(Opcode.DMUL);
		dload(c, 16);
		dload(c, 16);
		c.add(Opcode.DMUL);
		c.add(Opcode.DADD);
		dstore(c, 18);
		dload(c, 10);
		dload(c, 14);
		c.add(Opcode.DMUL);
		dload(c, 12);
		dload(c, 16);
		c.add(Opcode.DMUL);
		c.add(Opcode.DADD);
		dload(c, 18);
		c.add(Opcode.DDIV);
		emitBoxDouble(c, refs);
		astore(c, 6);
		dload(c, 12);
		dload(c, 14);
		c.add(Opcode.DMUL);
		dload(c, 10);
		dload(c, 16);
		c.add(Opcode.DMUL);
		c.add(Opcode.DSUB);
		dload(c, 18);
		c.add(Opcode.DDIV);
		emitBoxDouble(c, refs);
		astore(c, 7);
		emitNewHolderFromSlots(c, refs, 6, 7);
		c.add(Opcode.ARETURN);
		return new ComplexMethod(name, desc, c, 6, 20, List.of());
	}

	// _cneg(Object x): (-re, -im), each part through _neg (which keeps doubles
	// unboxed-negated, so signed zeros survive exactly like the interpreter's
	// negateReal/exactNeg).
	private static ComplexMethod buildNeg(Refs refs, Utf8Constant name, Utf8Constant desc) {
		List<Integer> c = new ArrayList<>();
		emitExtractParts(c, refs, 0, 1, 2);
		aload(c, 1);
		call(c, refs.rNeg());
		aload(c, 2);
		call(c, refs.rNeg());
		call(c, refs.rCComplex());
		c.add(Opcode.ARETURN);
		return new ComplexMethod(name, desc, c, 5, 3, List.of());
	}

	/** Pushes a double constant (with the -0.0 guard of the shared emitters). */
	private static void emitDoubleConst(List<Integer> c, ConstantPool cp, double value) {
		if (value == 0.0 && Double.doubleToRawLongBits(value) == 0L) {
			c.add(Opcode.DCONST_0);
		}
		else if (value == 1.0) {
			c.add(Opcode.DCONST_1);
		}
		else {
			ConstantPool.DoubleConstant dc = cp.addDouble(value);
			c.add(Opcode.LDC2_W);
			emitU2(c, dc.index());
		}
	}

	/**
	 * The principal square root of the doubles in {@code reSlot}/{@code imSlot}, into
	 * {@code r0Slot}/{@code r1Slot} (using {@code tSlot} for t). A double-zero answers
	 * its inputs unchanged, like {@code Environment.complexSqrt}.
	 */
	private static void emitComplexSqrtInto(List<Integer> c, Refs refs, ConstantPool cp, int reSlot, int imSlot,
			int r0Slot, int r1Slot, int tSlot) {
		dload(c, reSlot);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPL);
		int compute = jump(c, Opcode.IFNE);
		dload(c, imSlot);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPL);
		int compute2 = jump(c, Opcode.IFNE);
		dload(c, reSlot);
		dstore(c, r0Slot);
		dload(c, imSlot);
		dstore(c, r1Slot);
		int done = jump(c, Opcode.GOTO);
		patch(c, compute);
		patch(c, compute2);
		dload(c, reSlot);
		callMath(c, refs, cp, "abs", "(D)D");
		dload(c, reSlot);
		dload(c, imSlot);
		callMath(c, refs, cp, "hypot", "(DD)D");
		c.add(Opcode.DADD);
		emitDoubleConst(c, cp, 2.0);
		c.add(Opcode.DDIV);
		callMath(c, refs, cp, "sqrt", "(D)D");
		dstore(c, tSlot);
		dload(c, reSlot);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPL);
		int negative = jump(c, Opcode.IFLT);
		dload(c, tSlot);
		dstore(c, r0Slot);
		dload(c, imSlot);
		dload(c, tSlot);
		emitDoubleConst(c, cp, 2.0);
		c.add(Opcode.DMUL);
		c.add(Opcode.DDIV);
		dstore(c, r1Slot);
		int done2 = jump(c, Opcode.GOTO);
		patch(c, negative);
		dload(c, imSlot);
		callMath(c, refs, cp, "abs", "(D)D");
		dload(c, tSlot);
		emitDoubleConst(c, cp, 2.0);
		c.add(Opcode.DMUL);
		c.add(Opcode.DDIV);
		dstore(c, r0Slot);
		dload(c, tSlot);
		dload(c, imSlot);
		callMath(c, refs, cp, "copySign", "(DD)D");
		dstore(c, r1Slot);
		patch(c, done2);
		patch(c, done);
	}

	/**
	 * The complex logarithm of the doubles in {@code reSlot}/{@code imSlot}, leaving the
	 * two result doubles on the stack.
	 */
	private static void emitComplexLog(List<Integer> c, Refs refs, ConstantPool cp, int reSlot, int imSlot) {
		dload(c, reSlot);
		dload(c, imSlot);
		callMath(c, refs, cp, "hypot", "(DD)D");
		callMath(c, refs, cp, "log", "(D)D");
		dload(c, imSlot);
		dload(c, reSlot);
		callMath(c, refs, cp, "atan2", "(DD)D");
	}

	// _csqrt(Object x): the principal square root. A complex operand answers the
	// float formula; a negative real roots into the plane as (0, sqrt(-d)); any
	// other real answers Math.sqrt. Slots: param 0, boxed temps 1-2, doubles
	// 3-8.
	private static ComplexMethod buildSqrt(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		List<Integer> c = new ArrayList<>();
		aload(c, 0);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.rcClass().index());
		int realPath = jump(c, Opcode.IFEQ);
		aload(c, 0);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcReal().index());
		astore(c, 1);
		aload(c, 0);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcImag().index());
		astore(c, 2);
		emitToDouble(c, refs, 1);
		dstore(c, 3);
		emitToDouble(c, refs, 2);
		dstore(c, 5);
		emitComplexSqrtInto(c, refs, cp, 3, 5, 3, 5, 7);
		dload(c, 3);
		emitBoxDouble(c, refs);
		astore(c, 1);
		dload(c, 5);
		emitBoxDouble(c, refs);
		astore(c, 2);
		emitNewHolderFromSlots(c, refs, 1, 2);
		c.add(Opcode.ARETURN);
		patch(c, realPath);
		emitToDouble(c, refs, 0);
		dstore(c, 3);
		dload(c, 3);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPG);
		int nonNegative = jump(c, Opcode.IFGE);
		c.add(Opcode.DCONST_0);
		emitBoxDouble(c, refs);
		astore(c, 1);
		dload(c, 3);
		c.add(Opcode.DNEG);
		callMath(c, refs, cp, "sqrt", "(D)D");
		emitBoxDouble(c, refs);
		astore(c, 2);
		emitNewHolderFromSlots(c, refs, 1, 2);
		c.add(Opcode.ARETURN);
		patch(c, nonNegative);
		dload(c, 3);
		callMath(c, refs, cp, "sqrt", "(D)D");
		emitBoxDouble(c, refs);
		c.add(Opcode.ARETURN);
		return new ComplexMethod(name, desc, c, 6, 9, List.of());
	}

	// _cpow(Object base, Object exp): an int-range integer exponent over
	// non-float parts stays exact by repeated squaring (a negative one through
	// the exact reciprocal); anything else goes through exp(w*log(z)) in
	// floats. Slots: params 0-1, base parts 2-3, exp parts 4-5, power 6,
	// accumulators 7-8, temps 9-10, doubles 11-26.
	private static ComplexMethod buildPow(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		List<Integer> c = new ArrayList<>();
		aload(c, 0);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.doubleClass().index());
		int floatPathEarly = jump(c, Opcode.IFNE);
		aload(c, 0);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.rcClass().index());
		int baseNotHolder = jump(c, Opcode.IFEQ);
		aload(c, 0);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcReal().index());
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.doubleClass().index());
		int floatPathEarly2 = jump(c, Opcode.IFNE);
		aload(c, 0);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcImag().index());
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.doubleClass().index());
		int floatPathEarly3 = jump(c, Opcode.IFNE);
		patch(c, baseNotHolder);
		aload(c, 1);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.longClass().index());
		int floatPathExp = jump(c, Opcode.IFEQ);
		aload(c, 1);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.longClass().index());
		c.add(Opcode.INVOKEVIRTUAL);
		emitU2(c, refs.longValue().index());
		lstore(c, 9);
		lload(c, 9);
		ConstantPool.LongConstant maxPow = cp.addLong(Integer.MAX_VALUE);
		c.add(Opcode.LDC2_W);
		emitU2(c, maxPow.index());
		c.add(Opcode.LCMP);
		int floatPathRange = jump(c, Opcode.IFGT);
		lload(c, 9);
		ConstantPool.LongConstant minPow = cp.addLong(-(long) Integer.MAX_VALUE);
		c.add(Opcode.LDC2_W);
		emitU2(c, minPow.index());
		c.add(Opcode.LCMP);
		int floatPathRange2 = jump(c, Opcode.IFLT);
		lload(c, 9);
		c.add(Opcode.L2I);
		istore(c, 6);
		// Exact path.
		emitExtractParts(c, refs, 0, 2, 3);
		c.add(Opcode.LCONST_1);
		c.add(Opcode.INVOKESTATIC);
		emitU2(c, refs.longValueOf().index());
		astore(c, 7);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.INVOKESTATIC);
		emitU2(c, refs.longValueOf().index());
		astore(c, 8);
		iload(c, 6);
		int noRecip = jump(c, Opcode.IFGE);
		aload(c, 2);
		aload(c, 2);
		call(c, refs.rMul());
		aload(c, 3);
		aload(c, 3);
		call(c, refs.rMul());
		call(c, refs.rAdd());
		astore(c, 9);
		aload(c, 2);
		aload(c, 9);
		call(c, refs.rDiv());
		astore(c, 2);
		aload(c, 3);
		call(c, refs.rNeg());
		aload(c, 9);
		call(c, refs.rDiv());
		astore(c, 3);
		iload(c, 6);
		c.add(Opcode.INEG);
		istore(c, 6);
		patch(c, noRecip);
		int loopTop = c.size();
		iload(c, 6);
		int loopEnd = jump(c, Opcode.IFLE);
		iload(c, 6);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IAND);
		int skipMul = jump(c, Opcode.IFEQ);
		aload(c, 7);
		aload(c, 2);
		call(c, refs.rMul());
		aload(c, 8);
		aload(c, 3);
		call(c, refs.rMul());
		call(c, refs.rSub());
		astore(c, 9);
		aload(c, 7);
		aload(c, 3);
		call(c, refs.rMul());
		aload(c, 8);
		aload(c, 2);
		call(c, refs.rMul());
		call(c, refs.rAdd());
		astore(c, 8);
		aload(c, 9);
		astore(c, 7);
		patch(c, skipMul);
		aload(c, 2);
		aload(c, 2);
		call(c, refs.rMul());
		aload(c, 3);
		aload(c, 3);
		call(c, refs.rMul());
		call(c, refs.rSub());
		astore(c, 9);
		aload(c, 2);
		aload(c, 3);
		call(c, refs.rMul());
		aload(c, 3);
		aload(c, 2);
		call(c, refs.rMul());
		call(c, refs.rAdd());
		astore(c, 3);
		aload(c, 9);
		astore(c, 2);
		iload(c, 6);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.ISHR);
		istore(c, 6);
		int back = jump(c, Opcode.GOTO);
		patchAt(c, back, loopTop);
		patch(c, loopEnd);
		aload(c, 7);
		aload(c, 8);
		call(c, refs.rCComplex());
		c.add(Opcode.ARETURN);
		// Float path through exp(w*log(z)).
		int floatPath = c.size();
		patch(c, floatPathEarly);
		patch(c, floatPathEarly2);
		patch(c, floatPathEarly3);
		patch(c, floatPathExp);
		patch(c, floatPathRange);
		patch(c, floatPathRange2);
		emitExtractParts(c, refs, 0, 2, 3);
		emitExtractParts(c, refs, 1, 4, 5);
		emitToDouble(c, refs, 2);
		dstore(c, 11);
		emitToDouble(c, refs, 3);
		dstore(c, 13);
		emitToDouble(c, refs, 4);
		dstore(c, 15);
		emitToDouble(c, refs, 5);
		dstore(c, 17);
		dload(c, 11);
		dload(c, 13);
		callMath(c, refs, cp, "hypot", "(DD)D");
		callMath(c, refs, cp, "log", "(D)D");
		dstore(c, 19);
		dload(c, 13);
		dload(c, 11);
		callMath(c, refs, cp, "atan2", "(DD)D");
		dstore(c, 21);
		dload(c, 13);
		dload(c, 11);
		callMath(c, refs, cp, "atan2", "(DD)D");
		dstore(c, 21);
		dload(c, 15);
		dload(c, 19);
		c.add(Opcode.DMUL);
		dload(c, 17);
		dload(c, 21);
		c.add(Opcode.DMUL);
		c.add(Opcode.DSUB);
		callMath(c, refs, cp, "exp", "(D)D");
		dstore(c, 23);
		dload(c, 15);
		dload(c, 21);
		c.add(Opcode.DMUL);
		dload(c, 17);
		dload(c, 19);
		c.add(Opcode.DMUL);
		c.add(Opcode.DADD);
		dstore(c, 25);
		dload(c, 23);
		dload(c, 25);
		callMath(c, refs, cp, "cos", "(D)D");
		c.add(Opcode.DMUL);
		emitBoxDouble(c, refs);
		astore(c, 7);
		dload(c, 23);
		dload(c, 25);
		callMath(c, refs, cp, "sin", "(D)D");
		c.add(Opcode.DMUL);
		emitBoxDouble(c, refs);
		astore(c, 8);
		emitNewHolderFromSlots(c, refs, 7, 8);
		c.add(Opcode.ARETURN);
		return new ComplexMethod(name, desc, c, 8, 27, List.of());
	}

	private static void patchAt(List<Integer> c, int pos, int target) {
		JvmRuntimeBuilder.patchBranch(c, pos, target);
	}

	/**
	 * The asin sub-formula into {@code r0Slot}/{@code r1Slot}: s = sqrt(1-z^2), l =
	 * log(-im+s0, re+s1), answering (l1, -l0). Uses {@code t0Slot}/{@code t1Slot} and one
	 * more double slot for the sqrt inputs.
	 */
	private static void emitAsinInto(List<Integer> c, Refs refs, ConstantPool cp, int reSlot, int imSlot, int r0Slot,
			int r1Slot, int t0Slot, int t1Slot, int inSlot) {
		dload(c, reSlot);
		dload(c, reSlot);
		c.add(Opcode.DMUL);
		dload(c, imSlot);
		dload(c, imSlot);
		c.add(Opcode.DMUL);
		c.add(Opcode.DSUB);
		dstore(c, t0Slot);
		dload(c, reSlot);
		dload(c, imSlot);
		c.add(Opcode.DMUL);
		emitDoubleConst(c, cp, 2.0);
		c.add(Opcode.DMUL);
		dstore(c, t1Slot);
		emitDoubleConst(c, cp, 1.0);
		dload(c, t0Slot);
		c.add(Opcode.DSUB);
		dstore(c, inSlot);
		dload(c, t1Slot);
		c.add(Opcode.DNEG);
		dstore(c, t1Slot);
		emitComplexSqrtInto(c, refs, cp, inSlot, t1Slot, r0Slot, r1Slot, t0Slot);
		dload(c, imSlot);
		c.add(Opcode.DNEG);
		dload(c, r0Slot);
		c.add(Opcode.DADD);
		dstore(c, t0Slot);
		dload(c, reSlot);
		dload(c, r1Slot);
		c.add(Opcode.DADD);
		dstore(c, t1Slot);
		emitComplexLog(c, refs, cp, t0Slot, t1Slot);
		dstore(c, r1Slot);
		dstore(c, r0Slot);
	}

	// _cu1(Object x, int op): the unary math functions. A complex operand
	// answers the float formula; any other operand answers Math.<fn> of its
	// double. Slots: params 0-1, rd/d 2-3, id 4-5, t0 6-7, t1 8-9, r0 10-11,
	// r1 12-13, denom 14-15.
	private static ComplexMethod buildU1(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		List<Integer> c = new ArrayList<>();
		aload(c, 0);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.rcClass().index());
		int realPath = jump(c, Opcode.IFEQ);
		aload(c, 0);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcReal().index());
		astore(c, 6);
		aload(c, 0);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcImag().index());
		astore(c, 8);
		emitToDouble(c, refs, 6);
		dstore(c, 2);
		emitToDouble(c, refs, 8);
		dstore(c, 4);
		List<Integer> toTail = new ArrayList<>();
		String[] mathFns = { "exp", "log", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh" };
		for (int op = 0; op <= U1_TANH; op++) {
			int nextArm = -1;
			if (op < U1_TANH) {
				iload(c, 1);
				emitInt(c, op);
				nextArm = jump(c, Opcode.IF_ICMPNE);
			}
			emitComplexArm(c, refs, cp, op);
			if (op < U1_TANH) {
				toTail.add(jump(c, Opcode.GOTO));
				patch(c, nextArm);
			}
		}
		for (int i = 0; i < toTail.size(); i++) {
			patch(c, toTail.get(i));
		}
		dstore(c, 12);
		dstore(c, 10);
		dload(c, 10);
		emitBoxDouble(c, refs);
		astore(c, 6);
		dload(c, 12);
		emitBoxDouble(c, refs);
		astore(c, 8);
		emitNewHolderFromSlots(c, refs, 6, 8);
		c.add(Opcode.ARETURN);
		patch(c, realPath);
		emitToDouble(c, refs, 0);
		dstore(c, 2);
		for (int op = 0; op <= U1_TANH; op++) {
			if (op < U1_TANH) {
				iload(c, 1);
				emitInt(c, op);
				int next = jump(c, Opcode.IF_ICMPNE);
				dload(c, 2);
				callMath(c, refs, cp, mathFns[op], "(D)D");
				emitBoxDouble(c, refs);
				c.add(Opcode.ARETURN);
				patch(c, next);
			}
			else {
				dload(c, 2);
				callMath(c, refs, cp, mathFns[op], "(D)D");
				emitBoxDouble(c, refs);
				c.add(Opcode.ARETURN);
			}
		}
		return new ComplexMethod(name, desc, c, 8, 16, List.of());
	}

	/**
	 * One complex arm of {@link #buildU1}, leaving its two result doubles on the stack.
	 * Slots: rd 2-3, id 4-5, t0 6-7, t1 8-9, r0 10-11, r1 12-13, denom 14-15.
	 */
	private static void emitComplexArm(List<Integer> c, Refs refs, ConstantPool cp, int op) {
		if (op == U1_EXP) {
			dload(c, 2);
			callMath(c, refs, cp, "exp", "(D)D");
			dstore(c, 6);
			dload(c, 6);
			dload(c, 4);
			callMath(c, refs, cp, "cos", "(D)D");
			c.add(Opcode.DMUL);
			dload(c, 6);
			dload(c, 4);
			callMath(c, refs, cp, "sin", "(D)D");
			c.add(Opcode.DMUL);
		}
		else if (op == U1_LOG) {
			emitComplexLog(c, refs, cp, 2, 4);
		}
		else if (op == U1_SIN) {
			dload(c, 2);
			callMath(c, refs, cp, "sin", "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, "cosh", "(D)D");
			c.add(Opcode.DMUL);
			dload(c, 2);
			callMath(c, refs, cp, "cos", "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, "sinh", "(D)D");
			c.add(Opcode.DMUL);
		}
		else if (op == U1_COS) {
			dload(c, 2);
			callMath(c, refs, cp, "cos", "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, "cosh", "(D)D");
			c.add(Opcode.DMUL);
			dload(c, 2);
			callMath(c, refs, cp, "sin", "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, "sinh", "(D)D");
			c.add(Opcode.DMUL);
			c.add(Opcode.DNEG);
		}
		else if (op == U1_TAN || op == U1_TANH) {
			// s = (hyp1(re)*trig1(im), hyp2(re)*trig2(im)),
			// c = (hyp2(re)*trig1(im), -/+hyp1(re)*cis(im)) with the hyperbolic
			// sine on the imaginary side for tan and the circular sine for tanh,
			// matching complexSin/complexCos over complexSinh/complexCosh.
			String hyp1 = op == U1_TAN ? "sin" : "sinh";
			String hyp2 = op == U1_TAN ? "cos" : "cosh";
			String trig1 = op == U1_TAN ? "cosh" : "cos";
			String cisIm = op == U1_TAN ? "sinh" : "sin";
			String trig2 = op == U1_TAN ? "sinh" : "sin";
			dload(c, 2);
			callMath(c, refs, cp, hyp1, "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, trig1, "(D)D");
			c.add(Opcode.DMUL);
			dstore(c, 6);
			dload(c, 2);
			callMath(c, refs, cp, hyp2, "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, trig2, "(D)D");
			c.add(Opcode.DMUL);
			dstore(c, 8);
			dload(c, 2);
			callMath(c, refs, cp, hyp2, "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, trig1, "(D)D");
			c.add(Opcode.DMUL);
			dstore(c, 14);
			dload(c, 2);
			callMath(c, refs, cp, hyp1, "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, cisIm, "(D)D");
			c.add(Opcode.DMUL);
			if (op == U1_TAN) {
				c.add(Opcode.DNEG);
			}
			dstore(c, 2);
			dload(c, 14);
			dload(c, 14);
			c.add(Opcode.DMUL);
			dload(c, 2);
			dload(c, 2);
			c.add(Opcode.DMUL);
			c.add(Opcode.DADD);
			dstore(c, 14);
			dload(c, 6);
			dload(c, 14);
			c.add(Opcode.DMUL);
			dload(c, 8);
			dload(c, 2);
			c.add(Opcode.DMUL);
			c.add(Opcode.DADD);
			dload(c, 14);
			c.add(Opcode.DDIV);
			dload(c, 8);
			dload(c, 14);
			c.add(Opcode.DMUL);
			dload(c, 6);
			dload(c, 2);
			c.add(Opcode.DMUL);
			c.add(Opcode.DSUB);
			dload(c, 14);
			c.add(Opcode.DDIV);
		}
		else if (op == U1_ASIN) {
			emitAsinInto(c, refs, cp, 2, 4, 10, 12, 6, 8, 14);
			dload(c, 12);
			dload(c, 10);
			c.add(Opcode.DNEG);
		}
		else if (op == U1_ACOS) {
			emitAsinInto(c, refs, cp, 2, 4, 10, 12, 6, 8, 14);
			emitDoubleConst(c, cp, Math.PI / 2);
			dload(c, 10);
			c.add(Opcode.DSUB);
			dload(c, 12);
			c.add(Opcode.DNEG);
		}
		else if (op == U1_ATAN) {
			emitDoubleConst(c, cp, 1.0);
			dload(c, 4);
			c.add(Opcode.DADD);
			dstore(c, 6);
			dload(c, 2);
			c.add(Opcode.DNEG);
			dstore(c, 8);
			emitComplexLog(c, refs, cp, 6, 8);
			dstore(c, 8);
			dstore(c, 6);
			dload(c, 2);
			dstore(c, 10);
			emitDoubleConst(c, cp, 1.0);
			dload(c, 4);
			c.add(Opcode.DSUB);
			dstore(c, 2);
			dload(c, 10);
			dstore(c, 4);
			emitComplexLog(c, refs, cp, 2, 4);
			dstore(c, 4);
			dstore(c, 2);
			dload(c, 4);
			dload(c, 8);
			c.add(Opcode.DSUB);
			emitDoubleConst(c, cp, 2.0);
			c.add(Opcode.DDIV);
			dload(c, 6);
			dload(c, 2);
			c.add(Opcode.DSUB);
			emitDoubleConst(c, cp, 2.0);
			c.add(Opcode.DDIV);
		}
		else if (op == U1_SINH) {
			dload(c, 2);
			callMath(c, refs, cp, "sinh", "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, "cos", "(D)D");
			c.add(Opcode.DMUL);
			dload(c, 2);
			callMath(c, refs, cp, "cosh", "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, "sin", "(D)D");
			c.add(Opcode.DMUL);
		}
		else if (op == U1_COSH) {
			dload(c, 2);
			callMath(c, refs, cp, "cosh", "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, "cos", "(D)D");
			c.add(Opcode.DMUL);
			dload(c, 2);
			callMath(c, refs, cp, "sinh", "(D)D");
			dload(c, 4);
			callMath(c, refs, cp, "sin", "(D)D");
			c.add(Opcode.DMUL);
		}
		else {
			throw new IllegalArgumentException("unknown U1 op: " + op);
		}
	}

	// _ccmpb(Object a, Object b): like _cmpb, but a complex operand signals the
	// interpreter's "Expected real number" text instead of comparing.
	private static ComplexMethod buildCCmpBits(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		MethodrefConstant rCmpb = self(cp, refs.thisClass(), JvmNumericRuntimeBuilder.CMPB, CMP_DESC);
		List<Integer> c = new ArrayList<>();
		aload(c, 0);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.rcClass().index());
		int ifAReal = jump(c, Opcode.IFEQ);
		emitRealErrThrow(c, refs, 0);
		patch(c, ifAReal);
		aload(c, 1);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.rcClass().index());
		int ifBReal = jump(c, Opcode.IFEQ);
		emitRealErrThrow(c, refs, 1);
		patch(c, ifBReal);
		aload(c, 0);
		aload(c, 1);
		call(c, rCmpb);
		c.add(Opcode.IRETURN);
		return new ComplexMethod(name, desc, c, 4, 2, List.of());
	}

	/**
	 * Emits {@code throw new RuntimeException("Expected real number, got: " +
	 * _lispToString(value))} for the value in {@code slot}.
	 */
	private static void emitRealErrThrow(List<Integer> c, Refs refs, int slot) {
		c.add(Opcode.NEW);
		emitU2(c, refs.rteClass().index());
		c.add(Opcode.DUP);
		emitLdc(c, refs.realPrefix().index());
		aload(c, slot);
		c.add(Opcode.INVOKESTATIC);
		emitU2(c, refs.lispToString().index());
		c.add(Opcode.INVOKEVIRTUAL);
		emitU2(c, refs.strConcat().index());
		c.add(Opcode.INVOKESPECIAL);
		emitU2(c, refs.rteInit().index());
		c.add(Opcode.ATHROW);
	}

	// _cphase(Object x): the angle of a complex value, 0.0 for a non-negative
	// real and pi for a negative one. A non-number signals through the _dbl
	// funnel, like the interpreter's requireReal.
	private static ComplexMethod buildCPhase(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		List<Integer> c = new ArrayList<>();
		aload(c, 0);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.rcClass().index());
		int ifReal = jump(c, Opcode.IFEQ);
		aload(c, 0);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcImag().index());
		call(c, refs.rDbl());
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.numberClass().index());
		c.add(Opcode.INVOKEVIRTUAL);
		emitU2(c, refs.numDoubleValue().index());
		aload(c, 0);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcReal().index());
		call(c, refs.rDbl());
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.numberClass().index());
		c.add(Opcode.INVOKEVIRTUAL);
		emitU2(c, refs.numDoubleValue().index());
		callMath(c, refs, cp, "atan2", "(DD)D");
		emitBoxDouble(c, refs);
		c.add(Opcode.ARETURN);
		patch(c, ifReal);
		emitToDouble(c, refs, 0);
		dstore(c, 1);
		dload(c, 1);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPG);
		int ifNonNeg = jump(c, Opcode.IFGE);
		emitDoubleConst(c, cp, Math.PI);
		emitBoxDouble(c, refs);
		c.add(Opcode.ARETURN);
		patch(c, ifNonNeg);
		c.add(Opcode.DCONST_0);
		emitBoxDouble(c, refs);
		c.add(Opcode.ARETURN);
		return new ComplexMethod(name, desc, c, 4, 3, List.of());
	}

	// _cconjugate(Object x): (re, -im) for a holder, the value itself for a
	// real (signalling "Expected number" otherwise).
	private static ComplexMethod buildConjugate(Refs refs, Utf8Constant name, Utf8Constant desc) {
		List<Integer> c = new ArrayList<>();
		aload(c, 0);
		c.add(Opcode.INSTANCEOF);
		emitU2(c, refs.rcClass().index());
		int realOnly = jump(c, Opcode.IFEQ);
		aload(c, 0);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcReal().index());
		astore(c, 1);
		aload(c, 0);
		c.add(Opcode.CHECKCAST);
		emitU2(c, refs.rcClass().index());
		c.add(Opcode.GETFIELD);
		emitU2(c, refs.rcImag().index());
		call(c, refs.rNeg());
		astore(c, 2);
		aload(c, 1);
		aload(c, 2);
		call(c, refs.rCComplex());
		c.add(Opcode.ARETURN);
		patch(c, realOnly);
		List<Integer> realOk = new ArrayList<>();
		emitRequireReal(c, refs, 0, realOk);
		emitNumberErrThrow(c, refs, 0);
		for (int pos : realOk) {
			patch(c, pos);
		}
		aload(c, 0);
		c.add(Opcode.ARETURN);
		return new ComplexMethod(name, desc, c, 5, 3, List.of());
	}

}
