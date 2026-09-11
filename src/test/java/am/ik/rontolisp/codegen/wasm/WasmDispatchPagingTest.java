package am.ik.rontolisp.codegen.wasm;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The radix depth a paged dispatcher is built to, and the funcId range it accepts.
 *
 * <p>
 * Both exist because an unreachable bound must fail rather than hang: counting the depth
 * by shifting the funcId 8 more bits per round never terminates for an id of {@code 2^24}
 * or more, since Java takes a shift distance mod 32 -- the fourth round shifts by 0 and
 * reads the id straight back. A full {@code ./mvnw test} lost two workers to that loop
 * for 2223 s of CPU each before the count was closed-form
 * ({@code .kb/wasm-function-body-size.md}).
 */
class WasmDispatchPagingTest {

	@Test
	void levelCountTerminatesForEveryFuncId() {
		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			assertThat(WasmRuntimeBuilder.dispatchLevels(0)).isEqualTo(1);
			assertThat(WasmRuntimeBuilder.dispatchLevels(255)).isEqualTo(1);
			assertThat(WasmRuntimeBuilder.dispatchLevels(256)).isEqualTo(2);
			assertThat(WasmRuntimeBuilder.dispatchLevels(2978)).isEqualTo(2);
			assertThat(WasmRuntimeBuilder.dispatchLevels((1 << 16) - 1)).isEqualTo(2);
			assertThat(WasmRuntimeBuilder.dispatchLevels(1 << 16)).isEqualTo(3);
			// The two that used to spin: a funcId needing a fourth 8-bit digit.
			assertThat(WasmRuntimeBuilder.dispatchLevels(1 << 24)).isEqualTo(4);
			assertThat(WasmRuntimeBuilder.dispatchLevels(Integer.MAX_VALUE)).isEqualTo(4);
		});
	}

	@Test
	void aNegativeFuncIdCannotBePagedAndSaysSo() {
		assertTimeoutPreemptively(Duration.ofSeconds(10),
				() -> assertThatThrownBy(() -> WasmRuntimeBuilder.dispatchLevels(-1))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("-1"));
	}

	@Test
	void aFuncIdFromNoCounterIsRejectedRatherThanEmitted() {
		// One lambda, so the only funcId this compile could have handed out is 0; 2^24
		// is the value that used to reach the level count and hang there.
		WasmLispCompiler.LambdaInfo corrupt = new WasmLispCompiler.LambdaInfo(1 << 24, "_lambda_corrupt", List.of("x"),
				false, List.of(), List.of(), 0);
		assertTimeoutPreemptively(Duration.ofSeconds(30),
				() -> assertThatThrownBy(() -> WasmRuntimeBuilder.buildDispatchBody(1, List.of(), List.of(corrupt), 0,
						new WasmLispCompiler.StringTable(0, false, false), false, 0))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(String.valueOf(1 << 24))
					.hasMessageContaining("outside [0, 1)"));
	}

}
