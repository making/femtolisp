package am.ik.objc;

import java.util.List;
import java.util.Set;

/**
 * The selectors whose type encoding LIES: the nil-terminated Foundation constructors and
 * the format-string family, each declared exactly like its fixed-arity twin.
 *
 * <p>
 * {@code method_getTypeEncoding} describes every other method completely, which is what
 * makes {@link ObjcRuntime#send} honest -- a wrong selector, arity or operand type is an
 * {@link ObjcException} rather than a crash. A variadic method is the one hole in that:
 * {@code +[NSArray arrayWithObjects:]} is declared {@code @@:@}, byte for byte what
 * {@code +[NSArray arrayWithObject:]} is declared, and nothing in the runtime says which
 * of the two it is. On the Apple arm64 ABI that difference is the whole call -- a fixed
 * argument travels in a register, a variadic one on the STACK -- so sending
 * {@code arrayWithObjects:} through the declared shape leaves the callee walking its
 * {@code va_list} off a stack slot nobody wrote, and the process dies inside
 * {@code objc_retain}.
 *
 * <p>
 * The set is small and known, so it is written down here. {@link ObjcRuntime#send} gives
 * a selector in it the variadic treatment instead: arguments BEYOND the declared arity
 * are allowed, each is passed as a variadic argument, a nil terminator is appended, and
 * the downcall is bound with {@link java.lang.foreign.Linker.Option#firstVariadicArg} at
 * the declared arity. The appended nil is what the nil-terminated constructors need and
 * what the format family never reads: a {@code printf}-style callee consumes exactly what
 * its format string names, so one unread argument past the end costs nothing and keeps
 * ONE rule here instead of two.
 *
 * <p>
 * A variadic selector OUTSIDE this table -- one a program declares itself -- is still the
 * crash, because nothing can see it coming. That is the price of a runtime that does not
 * mark variadic methods, and it is why this is a table of names rather than a test.
 *
 * <p>
 * Pure data: nothing here touches the runtime, so it is testable on any platform.
 */
public final class VariadicSelectors {

	/**
	 * The nil-terminated constructors and the format-string family, in that order.
	 *
	 * <p>
	 * {@code arrayWithObjects:count:} is deliberately NOT one of them: it takes a real C
	 * array and a count, and is the fixed-arity way to build an array of any size.
	 */
	private static final Set<String> SELECTORS = Set.copyOf(List.of("arrayWithObjects:", "initWithObjects:",
			"setWithObjects:", "orderedSetWithObjects:", "dictionaryWithObjectsAndKeys:", "initWithObjectsAndKeys:",
			"stringWithFormat:", "initWithFormat:", "localizedStringWithFormat:", "stringByAppendingFormat:",
			"appendFormat:", "predicateWithFormat:", "raise:format:"));

	private VariadicSelectors() {
	}

	/**
	 * Whether a selector takes a variadic argument list the runtime does not declare.
	 * @param selector the selector name
	 * @return {@code true} when it is one of the known variadic selectors
	 */
	public static boolean isVariadic(String selector) {
		return SELECTORS.contains(selector);
	}

	/**
	 * Every selector in the table, for the tests that pin it against the shapes a native
	 * image registers.
	 * @return the selector names
	 */
	public static Set<String> all() {
		return SELECTORS;
	}

}
