package com.davfx.script;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.util.Lock;
import com.google.gson.JsonElement;

public class ContextTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContextTest.class);
	
	@Test
	public void test() throws Exception {
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			final Lock<JsonElement, Exception> lock = new Lock<>();
			final Lock<Boolean, Exception> endLock = new Lock<>();
			
			runner.register("trace", new SyncScriptFunction() {
				@Override
				public JsonElement call(JsonElement request) {
					LOGGER.debug("TRACE {}", request);
					lock.set(request);
					return null;
				}
			});
			runner.register("echo", new AsyncScriptFunction() {
				@Override
				public void call(JsonElement request, Callback callback) {
					LOGGER.debug("ECHO {}", request);
					callback.handle(request);
					callback.done();
				}
			});
			runner.prepare("var f = function(a, c) { echo(a, c); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("f end");
				}
			});
			
			ScriptRunner.Engine engine = runner.engine();
			engine.eval("f('aaa', function(r) { trace(r); });", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
					endLock.set(true);
				}
				@Override
				public void ended() {
					LOGGER.debug("f('aaa') end");
					endLock.set(true);
				}
			});
			
			Assertions.assertThat(endLock.waitFor()).isTrue();
			Assertions.assertThat(lock.waitFor().getAsString()).isEqualTo("aaa");
		}
	}

}
